# jobdash

A local, single-user dashboard for finding software engineering jobs — from LinkedIn, and from
company job boards (Greenhouse, Lever, Workday).

You trigger a run, it collects postings, stores them in a local SQLite file, filters out the
noise (seniority keywords, blocked companies), and gives you a table where you can click through
to the real posting and mark each one **Applied** or **Not interested**.

When the jobs come from an ATS board — which, unlike LinkedIn, gives you the full job
description — the last step of a run hands each description and your resume to the **Claude
CLI**, which sorts them into **Recommended match** and **Not recommended**, with a one-sentence
reason for each. That uses your existing Claude subscription; there is no API key and no API
credit spend.

It runs entirely on your machine. There is no server to deploy, no account to create, and no
credentials anywhere in the system.

---

## Where the jobs come from

| Source | Coverage | Description? | AI scan |
|---|---|---|---|
| **Greenhouse** | per company, 1 request for the whole board | yes | ✅ |
| **Lever** | per company, 1 request for the whole board | yes | ✅ |
| **Workday** | per company, server-side keyword search + 1 request per job | yes | ✅ |
| **LinkedIn** | broad search across all companies | **no** | ❌ |

**Results are restricted to the United States by default.** Company boards are worldwide — on a
real sample, 64% of collected postings were foreign — so the run form has a **United States only**
checkbox, ticked by default.

Location strings are free-form and wildly inconsistent (`"US - Austin, TX"`, `"Israel, Yokneam"`,
`"USA.VA.Reston"`, `"11 Locations"`), so **Claude decides** which are American rather than a list
of hardcoded patterns. Each distinct string is judged once and cached forever, which keeps this to
roughly one extra call per run. Where a string genuinely doesn't say where the job is, the posting
is **kept and marked `⚠ uncertain`** rather than dropped — and if the CLI is unavailable, postings
stay visible and are re-checked on the next run.

**Any mix of sources is one run.** LinkedIn is collected first under its own paced budget, then
the boards under theirs, then the run reads each new LinkedIn posting's public detail page for
its description (LinkedIn's search cards carry none; one paced request per job, capped per run -
see [Rate limiting](#rate-limiting)), and finally everything collected is AI-scanned together.

ATS boards are **per company** — there is no global search across them — so jobdash keeps a
catalog of companies and only visits the ones you enable. It ships with **42 companies verified
live**, and you can add more by hand or import thousands at once (see
[Job sources](#job-sources)).

---

## How it gets the data

LinkedIn has **no public jobs API**. Every product marketed as one is a scraper or a resold
dataset; the largest such vendor was sued by LinkedIn in January 2025 and shut down under a
permanent injunction.

This project uses LinkedIn's **anonymous guest endpoints** — the same server-rendered HTML a
logged-out visitor gets. Two rules follow from that, and they are not negotiable:

1. **Never authenticate.** No `li_at` cookie, no stored session, no headless browser with
   credentials. Cookie replay from a server IP is the clearest automation signal LinkedIn
   flags, and it escalates a recoverable IP throttle into an account restriction. Your real
   account is needed for actually applying to jobs and must not be exposed.
2. **Stay inside the request budget.** Roughly 200–300 requests/day from one residential IP,
   paced 6–12 seconds apart. Sustained rate matters far more than the daily total. This is
   enforced in code (see [Rate limiting](#rate-limiting)), not left to discipline.

A full US-wide sweep is about **50-100 search requests and ~15 minutes of wall clock**, plus one
detail request per passing job for its description - a few hundred more requests and roughly
another 45-60 minutes at the paced rate. Budget accordingly.

---

## Requirements

| Need | Version | Notes |
|---|---|---|
| JDK | 21+ | Built against 21, verified running on Temurin 25 |
| Node | 20+ | Verified on 26 |

Maven is **not** required — the repo ships the Maven wrapper (`./mvnw`). PostgreSQL and Docker
are **not** required; the database is a single SQLite file at `data/jobdash.db`, created on first
boot.

## Running it

```bash
./run.sh          # backend on :8080, dashboard on :5173
./stop.sh         # stops both
```

Then open **http://localhost:5173**.

`run.sh` refuses to start if either port is occupied, waits for each service to actually answer
HTTP before reporting success, and tears down anything it already started if the other fails.
Logs go to `logs/backend.log` and `logs/frontend.log`.

`stop.sh` shuts down **by listening port, not by recorded PID** — `mvnw spring-boot:run` and
`npm run dev` both spawn children that outlive their parent, so killing the recorded PID would
leave the real server holding the port. It is idempotent.

Other options:

```bash
./run.sh --backend-only     # API only, no dev server
./run.sh --frontend-only    # UI only, expects a backend already running
./import-lca.sh             # one-shot: load every DOL LCA .xlsx in data/lca (then deletes them)
./import-slugs.sh           # one-shot: import an ATS company catalog (imported rows start disabled)
./validate-slugs.py         # one-shot: probe every unverified catalog row live, delete the dead ones
```

### Running the pieces by hand

```bash
./mvnw spring-boot:run              # backend
cd frontend && npm install && npm run dev   # front end (first run needs the install)
```

## Tests

```bash
./mvnw test                         # 386 tests, no network access
cd frontend && npm run build        # tsc -b && vite build - type errors fail the build
cd frontend && npm run lint
```

**No test makes a live LinkedIn request.** Parser tests run against saved HTML fixtures in
`src/test/resources/fixtures/`; the rate limiter and circuit breaker use an injected fake clock
so they assert scheduling without sleeping.

---

## Using the dashboard

The dashboard has six top-level tabs — **Search**, **Results**, **Sources**, **Resumes**,
**Filters**, **Data**. Switching
between them keeps each panel's state (the results table's sort, min-salary filter and sub-tab
all survive), and a run in progress shows a live pill in the header from any tab.

**Start a run** (Search tab). First pick your **sources** from the dropdown — any mix of
Greenhouse, Lever and Workday, or LinkedIn on its own. Then keywords (default "Software
Engineer"), a time window in hours (default 24), and a location (default "United States").
For an ATS run you also pick which **resume** the AI scan compares against.

The LinkedIn-only controls (test mode, page cap, shard by metro) appear only when LinkedIn is
the selected source — they are LinkedIn pagination concepts and have no ATS equivalent. Enable
**test mode** to cap a LinkedIn run at 3 pages while you're experimenting; a full run is 100
requests, a test run is 3.

**Watch it live.** Progress streams over SSE: pages fetched, requests made, cards seen, new jobs.
There's a Cancel button. A 15-minute run behind a spinner would be unusable, so results land as
they're parsed. When the run finishes you're moved to **Results** automatically — but only if you
were still on the Search tab watching it.

**Triage** (Results tab — gets the full window, with a count badge). Three sub-tabs:

| Sub-tab | Shows |
|---|---|
| **Untriaged** | Passing jobs from the current run that you haven't triaged |
| **Applied** | Everything you marked Applied, **across all runs** |
| **Not interested** | Everything you dismissed, **across all runs** |

**AI match buckets.** Once a run has been scanned, the Untriaged list gains **All /
Recommended match / Not recommended** buckets with counts, plus a **Re-scan** button and an
**Apply with Claude** button (see [Applying with Claude](#applying-with-claude)). Each row
carries its one-sentence reason under the title. Rows that were never scanned — every LinkedIn
row, and anything the scan skipped — stay visible under **All** and are counted separately as
"not scanned", never silently dropped.

The status tabs deliberately ignore which run a job came from — a job you applied to last week
shouldn't vanish because this morning's run replaced the view.

Click any column header to sort by it; click again to reverse.

**Group by company.** A checkbox that collapses each company with more than one posting into a
single collapsed row (job count, distinct-location count, newest posting, top salary) that you
expand on demand. A company with exactly one job stays a normal row — grouping never buries a
one-off behind an expander. Grouping is applied *after* sorting and filtering, so the active sort
still decides both the order of the groups (by their best job) and the order within each one.
Useful when one prolific poster is drowning out everything else; a high location count on a
collapsed group is the same relay/spam smell the company-volume report looks for.

**Filters.** Exclude words match job **titles** (whole-word, case-insensitive), seeded with
`Senior, Sr, Staff, Principal, Lead, Manager, Director, Intern`. Blocked companies match the
full company name exactly. Editing either list **re-evaluates every job already stored**, not
just future ones — so removing a word brings previously-rejected jobs back.

**Salary.** During a run, each passing job is enriched with a salary range from public data —
US DOL H-1B LCA disclosure files (company-specific, imported locally), with the Adzuna and
h1bapi.com APIs as fallbacks. Results are cached per company+title for 90 days; data older
than 3 years is ignored. Jobs sort highest-salary-first, unknown salary last, and the
**Min salary** box hides jobs whose known salary is below a threshold (never hides
unknown-salary jobs). See [SALARY_SETUP.md](SALARY_SETUP.md) for API keys and the LCA import.

**Filters tab.** The exclude-word and blocked-company lists, plus the company-volume report.

**Data tab.** Database size on disk, row counts by verdict and status, and a
**Clear job data** button (two-step confirm, runs `VACUUM` so the file actually shrinks).

---

## Rate limiting

Three independent layers, all enforced in the sweep loop:

| Layer | Rule |
|---|---|
| Inter-request pacing | 6–12s jitter through a single global gate, measured from the **end** of the previous response |
| Per-run cap | 500 requests |
| Rolling 24h budget | 1000 requests, counted from the `request_log` table so a restart can't reset it |
| Detail phase | one request per passing LinkedIn job, every one of them, newest first; `sweep.detail.max-per-run` can cap it (0 = no cap) |

A request is a request: a search page and a job-detail fetch each cost one unit of every layer
above, because both hit the same host from the same IP. There is no separate "detail budget".

Plus a **circuit breaker**: HTTP 429 or 999 (LinkedIn's proprietary block code) opens it
immediately; a soft failure opens it after 2 consecutive. Cooldown starts at 30 minutes and
doubles per consecutive trip, capped at 60. Half-open admits exactly one probe, never a burst.
Breaker state is persisted, so restarting while blocked doesn't resume hammering.

**Clearing job data never deletes `request_log`.** That would let the Clear button double as a
way to reset the daily budget, defeating the mechanism it exists to enforce.

---

## Configuration

`src/main/resources/application.yml`:

```yaml
sweep:
  pacing:
    min-delay: 6s          # measured from end of previous response
    max-delay: 12s
  budget:
    per-run: 500             # search pages + detail fetches, together
    per-rolling-day: 1000
    test-mode-page-cap: 3
  detail:
    enabled: true
    max-per-run: 0           # descriptions fetched after collection, newest first; 0 = all of them
  breaker:
    open-duration: 30m
    max-open-duration: 60m
    soft-failure-threshold: 2
  shards:
    - "New York City Metropolitan Area"
    - "Los Angeles Metropolitan Area"
    - "Seattle, Washington, United States"
    - "Austin, Texas Metropolitan Area"
```

All four shard strings are empirically verified to return relevant results. Sharding is opt-in
per run and multiplies request cost — a US-wide search saturates LinkedIn's 1000-result cap, so
sharding is how you see past it, but four shards is up to four times the requests.

The same file has a `salary:` block (cache TTL, 3-year staleness cutoff, per-source pacing and
daily caps, API keys). API keys come from the environment (`.env`, sourced by `run.sh`) — see
[SALARY_SETUP.md](SALARY_SETUP.md).

It also has `ats:` and `ai:` blocks:

```yaml
ats:
  max-companies-per-run: 150   # a run never visits more enabled companies than this
  workday:
    max-details-per-run: 120   # Workday costs 1 extra request PER JOB for the description
  dead-slug-threshold: 2       # consecutive 404s before a board is retired
  daily-cap:                   # counted in external_request_log, separate from LinkedIn's budget
    greenhouse: 2000
    lever: 2000
    workday: 3000

ai:
  cli-path: ${CLAUDE_CLI_PATH:claude}
  model: sonnet
  batch-size: 9                # jobs per `claude` invocation
  concurrency: 3               # simultaneous `claude` processes
  max-description-chars: 6000
  us-only: true                # re-check location before scanning, as well as at collection

apply:
  enabled: true
  model: sonnet
  timeout: 10m                  # per job
  max-turns: 150                # browser actions per job; a full Greenhouse form needs ~100
  max-budget-usd: 4.0           # per job
  idle-timeout: 3m              # no browser action for this long = stuck, job killed
  max-description-chars: 4000
```

**`batch-size` is the cost lever that matters.** The cost shown in the UI is whatever the Claude
CLI reports for each invocation — jobdash sums it, it does not compute it. Every invocation carries
~24k tokens of fixed overhead no matter how small the payload, so a 2-job call ($0.10 measured) and
a 9-job call ($0.13 measured) cost almost the same. Scanning 27 jobs in 3 batches cost $0.40; one
job per call would have been about $2.70 for identical work. Raising `batch-size` lowers cost and
coarsens progress reporting; lowering it does the reverse.

**The ATS daily caps are not a scarce quota** the way the salary APIs' are — these are public,
documented JSON endpoints. The caps exist to bound a runaway loop, and they are counted
separately from LinkedIn's 300/day budget. Never conflate the two.

---

## Job sources

The **Sources** tab is the company catalog. A run only ever visits companies that are
**enabled** there and not marked dead.

### Importing a catalog

```bash
./stop.sh                 # the import writes to the same SQLite file as the server
./import-slugs.sh         # pulls the public open-jobs catalog
./import-slugs.sh path/to/slugs.json      # or a local file
./import-slugs.sh --prune                 # also delete rows already marked dead
```

The expected JSON shape is `{"ats": {"greenhouse": ["airbnb", ...], "lever": [...], "workday":
["nvidia.wd5.myworkdayjobs.com", ...]}}`. Only the three platforms jobdash implements are read,
so the same file keeps working as more are added.

**Imported companies arrive disabled.** The public catalog holds over 15,000 companies for those
three platforms alone; enabling them all would mean 15,000 requests per run. Enable the ones you
want in the Sources tab. Importing never touches a company you have already configured.

### Validating the catalog

```bash
./validate-slugs.py                # probe every status='unverified' row, then apply
./validate-slugs.py --dry-run      # probe and write the report only
./validate-slugs.py --ats workday  # one platform
```

Hits each board the way a run would (Greenhouse and Lever board endpoints, Workday
`robots.txt` + site-id probe) with only the Python standard library, at ~45 slugs/s. A definite
404 deletes the row, a live board becomes `status='active'` (still disabled) with its job count,
Greenhouse's real board name and, for Workday, the resolved site id. Transient failures leave
the row `unverified`. Enabled rows are never deleted, only listed in `flagged.csv`. Safe with
the backend running: it backs the DB up first and applies in one short transaction. Results
resume from `data/slug-validation/results.jsonl` if it is interrupted.

### Dead slugs retire themselves

Public catalogs go stale quickly — when this was last measured, 8 of 34 sampled Greenhouse slugs
and 7 of 8 sampled Lever slugs were already dead. A board that 404s on
`ats.dead-slug-threshold` consecutive runs is marked **dead** and disabled, and is never called
again. It is kept, not deleted, so you can see why a company vanished from your results.

---

## Resumes and the AI scan

Upload a resume in the **Resumes** tab — PDF, `.txt` or `.md`. A PDF must contain selectable
text; an image-only scan is rejected with a message saying so. You can keep several and pick
which one a run scans against; one is the default.

The scan runs automatically as the last step of any non-LinkedIn run, and the **Re-scan** button
in the Results tab re-runs it (after switching resumes, say). Results are cached per
(job, resume), so re-scanning already-scored jobs costs nothing.

**Watching a scan.** The run panel shows live numbers while it works — batches done, jobs
scored, the running recommended/not split, failed batches, cost and elapsed time. For the full
detail in a terminal:

```bash
tail -f logs/ai-scan.log
```

```
17:10:32 INFO  scan START run=13 resume="Backend - senior" scannable=4 cached=0 to-scan=4 batches=1
17:10:32 INFO  batch 1/1 START (4 jobs)
17:10:52 INFO  batch 1/1 done  20504ms  cost=$0.0743  recommended=2 not-recommended=2
17:10:52 INFO  scan DONE  run=13 scanned=4 recommended=2 not-recommended=2 skipped=0 elapsed=20s
```

That file gets scan activity only — Spring's own output stays in `logs/backend.log`. Progress is
reported per batch, so with the default `ai.batch-size: 9` a small scan is a single batch and
jumps straight from 0 to done; lower the batch size if you want finer movement.

It shells out to the `claude` binary on your PATH. If it isn't installed, `./run.sh` says so and
everything else still works — you just don't get scored results. Set `CLAUDE_CLI_PATH` if it
lives somewhere unusual.

---

## Applying with Claude

Once a run has scored jobs into the **Recommended match** bucket (Results tab, Untriaged sub-tab),
the **Apply with Claude** button next to Re-scan drives an actual browser through each one: it
opens the posting, finds the apply form, fills it from your resume and applicant profile, attaches
the resume file, and either stops at the review step or submits — your call, via the **Submit
applications** checkbox next to the button, **default off**.

**With Submit off** (the default), Claude fills the form, attaches the resume, and leaves the tab
open on the review step without touching the final Submit/Send button. The job's row gets a
**Needs review** badge and a one-line summary — read it, open the tab Claude left open, check the
answers, and submit yourself. This is deliberate: the model is told never to invent
work-authorization, sponsorship, salary or EEO answers, so anything it couldn't answer from your
resume or profile is left blank and listed in the row's notes for you to fill in by hand.

**With Submit on**, a successful application clicks through the confirmation page too and the job
moves straight to **Applied**.

**The applicant profile.** A resume answers "what have you done," not "are you authorized to work
here" or "what's your salary expectation" — those need a real answer, and Claude is never allowed
to guess one. Fill in the **applicant profile** form at the bottom of the **Resumes** tab once
(name, contact info, links, work authorization, sponsorship, salary expectation, and a free-text
field for anything else you're regularly asked) and every application draws on it.

**LinkedIn rows are skipped, listed as "Apply manually."** Applying through LinkedIn needs your
real, logged-in LinkedIn account, and this project's one non-negotiable rule (see
[How it gets the data](#how-it-gets-the-data)) is that account must never be exposed to
automation. Only Greenhouse / Lever / Workday postings get the Claude-driven flow.

**Where Claude is sent.** Greenhouse and Lever both serve the application form as a standalone
page (Greenhouse's `embed/job_app` URL, Lever's `/apply`), so Claude opens that directly instead
of the company's own careers page - several companies (Stripe, for one) embed the form in a
cross-origin iframe there, which the browser extension cannot see into. **Workday postings need a
candidate account** on each company's Workday tenant; Claude will not create one, so those end as
**Apply failed** with "Workday account required" until you have signed up there yourself.

**Prerequisites**, one-time:

- Google Chrome (or another Chromium browser — Edge, Brave, etc.)
- The [Claude in Chrome extension](https://chromewebstore.google.com/detail/claude/fcoeoabgfenejglbffodgkkbkcdhcgfn)
  (≥1.0.36) installed
- Claude Code signed in via `/login` — an API key or setup-token disables Chrome integration
- Run `claude --chrome` once, interactively, from this repo's directory to accept the one-time
  permission dialog, **then restart Chrome** — it only reads its native-messaging host list at
  startup, so the extension won't connect until you do
- `./run.sh` checks for this and warns if it isn't done yet; everything else works without it

Claude drives your **real** Chrome window, opening tabs you can watch — nothing runs headless or
out of sight.

**Cost.** Each application is its own `claude -p --chrome` invocation, so expect roughly
**$1–$3 per application** depending on how many form fields and browser actions it takes (a full
Greenhouse form with education and EEO questions is 80–120 browser actions). Keep the laptop
awake: the batch runs `caffeinate -i` itself, but a closed lid still sleeps the machine and pauses
the job until it wakes.
The batch view shows a running total from the CLI's own reported cost.

**Watching it, and unsticking it.** While a batch runs the Results tab shows which job is in
progress and, under it, the last thing Claude did (`Claude: tool …navigate {"url": …}`), plus a
Cancel button. A **Details** toggle lists every job in the batch with its notes, a **View log** link
that opens the full per-job transcript, and a **Resume in terminal** command. Three levels of
detail, from a terminal:

```bash
tail -f logs/apply.log                         # one line per browser action, every job
less logs/apply/batch-<batch>-job-<jobId>.log   # the full transcript of one application
claude --resume <sessionId> --chrome            # continue that exact Claude session and ask it
```

The third is the one to reach for when Claude gets stuck: each application runs in its own Claude
session, the session id is kept on the job (copy the command from Details), and resuming it puts
you in an interactive conversation with the same context — ask what blocked it, or tell it what to
do next. The tabs it used are closed once the session ends, but it can reopen them.

A job that produces no browser action for `apply.idle-timeout` (3 minutes) is killed and marked
**Apply failed** with a "Stuck:" note — the usual cause is a native macOS file-picker dialog, which
freezes the page, and the prompt now tells Claude never to click the button that opens one.

`logs/apply.log` never contains your resume text, your applicant profile, or the prompt — only job
titles, browser actions, outcomes and timings. The per-job transcripts under `logs/apply/` **do**
contain the values Claude typed into form fields, because that is exactly what you need to see when
debugging one; `logs/` is gitignored.

---

## What's not built yet

Deliberately out of scope for this version, with the seams left in place:

- **Showing descriptions in the UI** — LinkedIn descriptions are fetched (since 2026-09-09) and
  read by the AI scan, but no tab displays the text itself yet; `JobResponse` doesn't carry it.
- **Relay/spam detection** — `description_hash` is now populated for fetched LinkedIn rows, so the
  duplicate-body signal HANDOFF.md §5 describes is finally computable. Nothing reads it yet.
- **More ATS platforms** — the catalog file already lists 25 (Ashby, Workable, SmartRecruiters,
  Recruitee, …); three are implemented. Adding a fourth means one `JobSource` implementation —
  see the "Add a NEW ATS platform" row in [CODEMAP.md](CODEMAP.md).
- **Relay/spam detection** — the duplicate-description signal is now possible for ATS rows but
  isn't wired up; only the company-volume report exists today.
- **Scheduling** — runs are manual only.

See [HANDOFF.md](HANDOFF.md) for why each of these was cut and what it would take to add them,
and [CODEMAP.md](CODEMAP.md) for a tour of the code.
