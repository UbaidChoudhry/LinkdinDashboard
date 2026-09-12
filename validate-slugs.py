#!/usr/bin/env python3
"""
Validates the ATS slug catalog in ats_company against the live Greenhouse / Lever / Workday
endpoints, then removes the rows that are dead and marks the rest verified.

    ./validate-slugs.py                 validate every status='unverified' row and apply
    ./validate-slugs.py --dry-run       validate, write the report, change nothing in the DB
    ./validate-slugs.py --ats lever     one platform only
    ./validate-slugs.py --limit 20      a smoke test
    ./validate-slugs.py --fresh         discard a previous partial run instead of resuming it

What "valid" means, per platform - the same rules the run-time adapters apply
(source/greenhouse, source/lever, source/workday), so a slug that passes here is one a run
can actually use:

  greenhouse  GET boards-api.greenhouse.io/v1/boards/{slug}        200 = live, 404 = dead
              GET .../v1/boards/{slug}/jobs                        meta.total -> last_job_count
  lever       GET api.lever.co/v0/postings/{slug}?mode=json        200 = live (an empty list is
                                                                   a LIVE board), 404 = dead
  workday     GET https://{host}/robots.txt  ->  Allow: /{site}/ candidates, plus a few
              conventional guesses; POST /wday/cxs/{tenant}/{site}/jobs (limit 1) - the first
              HTTP 200 wins. No candidate answering 200, or a host that does not resolve in
              DNS, is dead (the adapter would report DeadSlug for it too).

A live Greenhouse board whose name says it is a test/demo/sandbox board is reported as
"not_real" and removed with the dead ones.

Anything transient (timeouts, 5xx, 429 after retries) is "error": the row is left
'unverified' and untouched so a later run can pick it up. Only a definite answer changes a row.

Rows the user has enabled (enabled=1) are NEVER deleted, whatever the probe says - they are
listed in the report instead. Removing them is a decision, not a side effect.

Safe to run with the backend up: the probe phase is network-only, and the apply phase is one
short WAL transaction behind a 60s busy timeout, after an online backup of the DB file. It
refuses to apply while a sweep run is in flight, because a run writes to the same rows.

Outputs, under --out (default data/slug-validation/):
  results.jsonl   one line per probed slug, appended as results arrive (this is what --resume reads)
  summary.json    counts per platform and outcome, plus what was changed
  removed.csv     every row deleted, with enough columns to re-add it by hand
  flagged.csv     rows that need a human: enabled rows that look dead, and not_real boards

Requires only the Python 3 standard library.
"""

import argparse
import csv
import json
import os
import re
import socket
import sqlite3
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

GREENHOUSE = "greenhouse"
LEVER = "lever"
WORKDAY = "workday"
ALL_ATS = (GREENHOUSE, LEVER, WORKDAY)

# Workday serves robots.txt only to something that looks like a browser on some tenants
# (mirrors WorkdaySiteResolver). Harmless on the other two platforms.
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
              "Chrome/124.0.0.0 Safari/537.36")
ALLOW_LINE = re.compile(r"(?im)^Allow:\s*/([^/\s]+)/")
# Whole words only - "TestGorilla" and "Testlio" are real companies on Greenhouse - plus the
# glued-together forms seen in the catalog ("testjobpost"). Every hit lands in flagged.csv.
NOT_REAL_NAME = re.compile(r"\b(test|testing|tester|demo|sandbox|sample|example|dummy|placeholder|fake)\b"
                           r"|do not use|\btest(job|board|company|post|account|org|site)", re.IGNORECASE)

HTTP_TIMEOUT = 25
RETRY_DELAYS = (1.0, 3.0, 8.0)          # after the first attempt; 429 waits longer (below)
RATE_LIMIT_DELAY = 15.0
MAX_BODY = 32 * 1024 * 1024             # Lever boards are fetched whole; Veeva's is ~12 MB

SSL_CONTEXT = ssl.create_default_context()


# --------------------------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------------------------

class Transient(Exception):
    """A failure that says nothing definite about the slug: timeout, 5xx, 429, connection reset."""


class HostGone(Exception):
    """The host does not exist in DNS - a definite answer for a Workday tenant."""


def http(method, url, body=None, headers=None):
    """Returns (status, body_text). Raises Transient after exhausting retries, HostGone on NXDOMAIN.

    A 404 is returned, not raised: it is the answer the caller is looking for.
    """
    hdrs = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if headers:
        hdrs.update(headers)
    data = body.encode("utf-8") if body is not None else None
    if data is not None:
        hdrs["Content-Type"] = "application/json"

    last = None
    for attempt in range(len(RETRY_DELAYS) + 1):
        try:
            req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT, context=SSL_CONTEXT) as resp:
                return resp.status, resp.read(MAX_BODY).decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                last = "HTTP %d" % e.code
                time.sleep(RATE_LIMIT_DELAY if e.code == 429 else RETRY_DELAYS[min(attempt, len(RETRY_DELAYS) - 1)])
                continue
            # 4xx other than 429 is a definite answer about this URL.
            return e.code, e.read(MAX_BODY).decode("utf-8", "replace") if e.fp else ""
        except urllib.error.URLError as e:
            reason = e.reason
            if isinstance(reason, socket.gaierror):
                # EAI_NONAME (8 on macOS, -2 on Linux) = the name does not exist. Anything else
                # (EAI_AGAIN etc.) is the resolver having a bad moment, not the host being gone.
                if reason.errno in (8, -2, socket.EAI_NONAME):
                    raise HostGone(str(reason))
            last = str(reason)
        except (socket.timeout, ConnectionError, ssl.SSLError, OSError) as e:
            last = "%s: %s" % (type(e).__name__, e)
        if attempt < len(RETRY_DELAYS):
            time.sleep(RETRY_DELAYS[attempt])
    raise Transient(last or "unknown transport failure")


# --------------------------------------------------------------------------------------------
# Per-platform probes. Each returns a dict with at least {"outcome": live|dead|error, "detail": str}
# --------------------------------------------------------------------------------------------

def probe_greenhouse(row):
    slug = row["slug"]
    try:
        status, body = http("GET", "https://boards-api.greenhouse.io/v1/boards/%s" % slug)
    except Transient as e:
        return {"outcome": "error", "detail": str(e)}
    if status == 404:
        return {"outcome": "dead", "detail": "HTTP 404 board not found"}
    if status != 200:
        return {"outcome": "error", "detail": "HTTP %d on board" % status}
    try:
        name = (json.loads(body).get("name") or "").strip()
    except ValueError:
        return {"outcome": "error", "detail": "unparseable board JSON"}

    result = {"outcome": "live", "detail": "HTTP 200", "name": name or None}
    if not name or NOT_REAL_NAME.search(name):
        result["outcome"] = "not_real"
        result["detail"] = "board name looks like a test board: %r" % name

    # Job count is a nice-to-have for choosing what to enable; a failure here changes nothing.
    try:
        status, body = http("GET", "https://boards-api.greenhouse.io/v1/boards/%s/jobs" % slug)
        if status == 200:
            result["job_count"] = int(json.loads(body).get("meta", {}).get("total", 0))
    except (Transient, ValueError, TypeError):
        pass
    return result


def probe_lever(row):
    slug = row["slug"]
    try:
        status, body = http("GET", "https://api.lever.co/v0/postings/%s?mode=json" % slug)
    except Transient as e:
        return {"outcome": "error", "detail": str(e)}
    if status == 404:
        return {"outcome": "dead", "detail": "HTTP 404 board not found"}
    if status != 200:
        return {"outcome": "error", "detail": "HTTP %d" % status}
    try:
        postings = json.loads(body)
    except ValueError:
        return {"outcome": "error", "detail": "unparseable postings JSON"}
    if not isinstance(postings, list):
        # A 200 that is not a list is not something the adapter handles either; don't guess.
        return {"outcome": "error", "detail": "HTTP 200 but body is not a list"}
    # An empty list is a LIVE board with no open jobs (kraken, wealthsimple, ...). Never dead.
    return {"outcome": "live", "detail": "HTTP 200", "job_count": len(postings)}


def workday_guesses(tenant):
    cap = tenant[:1].upper() + tenant[1:]
    seen = []
    for s in ("External", "Careers", "careers", "External_Careers", "ExternalCareers", tenant, cap,
              tenant + "careers", cap + "Careers", cap + "_Careers", "External_Career_Site", "jobs",
              "Search", "Career", "Careers_External"):
        if s not in seen:
            seen.append(s)
    return seen


def probe_workday(row):
    host = row["host"]
    if not host:
        return {"outcome": "error", "detail": "row has no host"}
    tenant = host.split(".", 1)[0]

    candidates = []
    robots_note = ""
    try:
        status, body = http("GET", "https://%s/robots.txt" % host, headers={"Accept": "*/*"})
        if status == 200:
            candidates = [s for s in ALLOW_LINE.findall(body)]
            if not candidates:
                robots_note = "robots.txt has no Allow: lines"
        else:
            robots_note = "robots.txt HTTP %d" % status
    except HostGone as e:
        return {"outcome": "dead", "detail": "host does not resolve: %s" % e}
    except Transient as e:
        return {"outcome": "error", "detail": "robots.txt: %s" % e}

    # A site the row already carries goes first: it was verified once.
    if row.get("site"):
        candidates.insert(0, row["site"])
    for g in workday_guesses(tenant):
        if g not in candidates:
            candidates.append(g)

    body_json = '{"appliedFacets":{},"limit":1,"offset":0,"searchText":""}'
    transient = None
    for site in candidates:
        url = "https://%s/wday/cxs/%s/%s/jobs" % (host, tenant, site)
        try:
            status, body = http("POST", url, body=body_json)
        except HostGone as e:
            return {"outcome": "dead", "detail": "host does not resolve: %s" % e}
        except Transient as e:
            transient = str(e)
            continue
        if status == 200:
            total = None
            try:
                total = int(json.loads(body).get("total"))
            except (ValueError, TypeError, AttributeError):
                pass
            return {"outcome": "live", "detail": "HTTP 200 via site %s" % site, "site": site,
                    "job_count": total}
    if transient:
        return {"outcome": "error", "detail": "some candidates unreachable (%s); %s" % (transient, robots_note)}
    return {"outcome": "dead", "detail": "no site id answered 200 (%d tried; %s)" % (len(candidates), robots_note or "robots ok")}


PROBES = {GREENHOUSE: probe_greenhouse, LEVER: probe_lever, WORKDAY: probe_workday}


# --------------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------------

def now_iso():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def load_candidates(db_path, ats_list, include_active, limit):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    statuses = ("unverified", "active") if include_active else ("unverified",)
    sql = ("select id, ats, slug, company, host, site, enabled, status from ats_company "
           "where status in (%s) and ats in (%s) order by ats, slug"
           % (",".join("?" * len(statuses)), ",".join("?" * len(ats_list))))
    rows = [dict(r) for r in con.execute(sql, (*statuses, *ats_list))]
    con.close()
    if limit:
        per = {}
        rows = [r for r in rows if per.setdefault(r["ats"], []).append(r) is None and len(per[r["ats"]]) <= limit]
    return rows


def read_previous(results_path):
    done = {}
    if not os.path.exists(results_path):
        return done
    with open(results_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except ValueError:
                continue
            # An earlier "error" is worth retrying; a definite answer is not.
            if r.get("outcome") != "error":
                done[r["id"]] = r
    return done


def probe_all(rows, workers, results_path, previous):
    todo = [r for r in rows if r["id"] not in previous]
    results = dict(previous)
    lock = threading.Lock()
    counts = {"done": 0, "total": len(todo)}
    started = time.time()

    out = open(results_path, "a")

    def one(row):
        t0 = time.time()
        try:
            res = PROBES[row["ats"]](row)
        except Exception as e:  # a probe must never take the run down
            res = {"outcome": "error", "detail": "probe crashed: %s: %s" % (type(e).__name__, e)}
        rec = {"id": row["id"], "ats": row["ats"], "slug": row["slug"], "host": row.get("host"),
               "enabled": row["enabled"], "checked_at": now_iso(), "took_ms": int((time.time() - t0) * 1000)}
        rec.update(res)
        with lock:
            results[row["id"]] = rec
            out.write(json.dumps(rec) + "\n")
            out.flush()
            counts["done"] += 1
            n = counts["done"]
            if n % 100 == 0 or n == counts["total"]:
                elapsed = time.time() - started
                rate = n / elapsed if elapsed else 0
                eta = (counts["total"] - n) / rate if rate else 0
                print("  %d/%d probed  %.1f/s  eta %dm%02ds" % (n, counts["total"], rate, eta // 60, eta % 60),
                      flush=True)
        return rec

    # One pool per platform so a slow Workday tenant never starves Greenhouse, and each
    # platform's host sees a bounded number of concurrent connections.
    by_ats = {}
    for r in todo:
        by_ats.setdefault(r["ats"], []).append(r)
    threads = []
    for ats, group in by_ats.items():
        def run_group(ats=ats, group=group):
            with ThreadPoolExecutor(max_workers=workers[ats]) as pool:
                list(pool.map(one, group))
        t = threading.Thread(target=run_group, name="pool-" + ats)
        t.start()
        threads.append(t)
    for t in threads:
        t.join()
    out.close()
    return results


def backup_db(db_path):
    backups = os.path.join(os.path.dirname(os.path.abspath(db_path)), "backups")
    os.makedirs(backups, exist_ok=True)
    target = os.path.join(backups, "jobdash-%s-pre-slug-validate.db" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    src = sqlite3.connect(db_path)
    dst = sqlite3.connect(target)
    with dst:
        src.backup(dst)          # the online backup API: consistent even with the backend running
    dst.close()
    src.close()
    return target


def apply(db_path, rows, results, dry_run):
    by_id = {r["id"]: r for r in rows}
    to_delete, to_activate, to_touch, flagged = [], [], [], []
    for rid, res in results.items():
        row = by_id.get(rid)
        if row is None:
            continue
        outcome = res["outcome"]
        if outcome in ("dead", "not_real"):
            if row["enabled"]:
                flagged.append((row, res, "enabled row looks %s - not deleted" % outcome))
            else:
                to_delete.append((row, res))
                if outcome == "not_real":
                    flagged.append((row, res, "removed as not a real board"))
        elif outcome == "live":
            to_activate.append((row, res))
        else:
            to_touch.append((row, res))

    changes = {"deleted": len(to_delete), "activated": len(to_activate), "left_unverified": len(to_touch),
               "flagged": len(flagged), "backup": None, "applied": not dry_run}
    if dry_run:
        return changes, to_delete, flagged

    con = sqlite3.connect(db_path, timeout=60)
    con.execute("pragma busy_timeout = 60000")
    running = con.execute("select count(*) from sweep_run where status = 'running'").fetchone()[0]
    if running:
        con.close()
        raise SystemExit("error: a sweep run is in flight; it writes ats_company rows. Re-run once it finishes "
                         "(the probe results are saved - use the default resume).")

    changes["backup"] = backup_db(db_path)
    ts = now_iso()
    with con:
        con.executemany("delete from ats_company where id = ? and enabled = 0",
                        [(row["id"],) for row, _ in to_delete])
        for row, res in to_activate:
            con.execute(
                """update ats_company
                      set status = 'active', consecutive_failures = 0,
                          last_checked_at = ?, last_ok_at = ?,
                          last_job_count = coalesce(?, last_job_count),
                          site = coalesce(?, site),
                          company = case when lower(company) = lower(slug) and ? is not null then ? else company end
                    where id = ?""",
                (ts, ts, res.get("job_count"), res.get("site"), res.get("name"), res.get("name"), row["id"]))
        con.executemany("update ats_company set last_checked_at = ? where id = ?",
                        [(ts, row["id"]) for row, _ in to_touch])
    con.close()
    return changes, to_delete, flagged


def write_reports(out_dir, rows, results, changes, to_delete, flagged):
    summary = {"generated_at": now_iso(), "candidates": len(rows), "by_ats": {}, "changes": changes}
    for r in results.values():
        s = summary["by_ats"].setdefault(r["ats"], {"live": 0, "dead": 0, "not_real": 0, "error": 0})
        s[r["outcome"]] = s.get(r["outcome"], 0) + 1
    with open(os.path.join(out_dir, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    with open(os.path.join(out_dir, "removed.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "ats", "slug", "company", "host", "outcome", "detail"])
        for row, res in sorted(to_delete, key=lambda x: (x[0]["ats"], x[0]["slug"])):
            w.writerow([row["id"], row["ats"], row["slug"], row["company"], row.get("host") or "",
                        res["outcome"], res["detail"]])

    with open(os.path.join(out_dir, "flagged.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "ats", "slug", "company", "enabled", "outcome", "detail", "note"])
        for row, res, note in sorted(flagged, key=lambda x: (x[0]["ats"], x[0]["slug"])):
            w.writerow([row["id"], row["ats"], row["slug"], row["company"], row["enabled"],
                        res["outcome"], res["detail"], note])
    return summary


def print_summary(summary):
    print()
    print("%-11s %8s %8s %9s %7s" % ("platform", "live", "dead", "not_real", "error"))
    tot = {"live": 0, "dead": 0, "not_real": 0, "error": 0}
    for ats in ALL_ATS:
        s = summary["by_ats"].get(ats)
        if not s:
            continue
        print("%-11s %8d %8d %9d %7d" % (ats, s["live"], s["dead"], s["not_real"], s["error"]))
        for k in tot:
            tot[k] += s.get(k, 0)
    print("%-11s %8d %8d %9d %7d" % ("total", tot["live"], tot["dead"], tot["not_real"], tot["error"]))
    c = summary["changes"]
    print()
    print("%s: %d rows deleted, %d marked active, %d left unverified (transient errors), %d flagged"
          % ("APPLIED" if c["applied"] else "DRY RUN - would be", c["deleted"], c["activated"],
             c["left_unverified"], c["flagged"]))
    if c.get("backup"):
        print("backup: %s" % c["backup"])


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default=os.path.join(here, "data", "jobdash.db"))
    ap.add_argument("--out", default=os.path.join(here, "data", "slug-validation"))
    ap.add_argument("--ats", default=",".join(ALL_ATS), help="comma-separated subset of greenhouse,lever,workday")
    ap.add_argument("--include-active", action="store_true", help="also re-check rows already marked active")
    ap.add_argument("--limit", type=int, default=0, help="probe at most N rows per platform (smoke test)")
    ap.add_argument("--workers", default="greenhouse=8,lever=6,workday=6")
    ap.add_argument("--dry-run", action="store_true", help="probe and report, but do not touch the database")
    ap.add_argument("--fresh", action="store_true", help="ignore a previous results.jsonl instead of resuming it")
    args = ap.parse_args()

    ats_list = [a.strip() for a in args.ats.split(",") if a.strip()]
    for a in ats_list:
        if a not in ALL_ATS:
            ap.error("unknown ats %r" % a)
    workers = {a: 4 for a in ALL_ATS}
    for part in args.workers.split(","):
        k, _, v = part.partition("=")
        if k.strip() in workers and v.strip().isdigit():
            workers[k.strip()] = max(1, int(v))
    if not os.path.exists(args.db):
        ap.error("database not found: %s" % args.db)

    os.makedirs(args.out, exist_ok=True)
    results_path = os.path.join(args.out, "results.jsonl")
    if args.fresh and os.path.exists(results_path):
        os.remove(results_path)

    rows = load_candidates(args.db, ats_list, args.include_active, args.limit)
    previous = read_previous(results_path)
    previous = {k: v for k, v in previous.items() if k in {r["id"] for r in rows}}
    per_ats = {}
    for r in rows:
        per_ats[r["ats"]] = per_ats.get(r["ats"], 0) + 1
    print("candidates: %d  (%s)" % (len(rows), ", ".join("%s=%d" % kv for kv in sorted(per_ats.items()))))
    if previous:
        print("resuming: %d already probed in %s" % (len(previous), results_path))
    print("workers: %s   results -> %s" % (", ".join("%s=%d" % kv for kv in sorted(workers.items())), results_path))

    started = time.time()
    results = probe_all(rows, workers, results_path, previous)
    print("probed %d rows in %dm%02ds" % (len(results), (time.time() - started) // 60, (time.time() - started) % 60))

    changes, to_delete, flagged = apply(args.db, rows, results, args.dry_run)
    summary = write_reports(args.out, rows, results, changes, to_delete, flagged)
    print_summary(summary)
    print("reports: %s/{summary.json,removed.csv,flagged.csv,results.jsonl}" % args.out)


if __name__ == "__main__":
    main()
