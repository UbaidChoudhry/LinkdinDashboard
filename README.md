# jobdash

A local, single-user dashboard for finding software engineering jobs on LinkedIn.

You trigger a run, it pages through LinkedIn's public job search, stores what it finds in a
local SQLite file, filters out the noise (seniority keywords, blocked companies), and gives you
a table where you can click through to the real posting and mark each one **Applied** or
**Not interested**.

It runs entirely on your machine. There is no server to deploy, no account to create, and no
credentials anywhere in the system.

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

A full US-wide sweep is about **100 requests and ~15 minutes of wall clock**. Budget accordingly.

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
./import-lca.sh <file.xlsx> # one-shot: load a DOL LCA disclosure file for salary data
```

### Running the pieces by hand

```bash
./mvnw spring-boot:run              # backend
cd frontend && npm install && npm run dev   # front end (first run needs the install)
```

## Tests

```bash
./mvnw test                         # 188 tests, no network access
cd frontend && npm run build        # tsc -b && vite build - type errors fail the build
cd frontend && npm run lint
```

**No test makes a live LinkedIn request.** Parser tests run against saved HTML fixtures in
`src/test/resources/fixtures/`; the rate limiter and circuit breaker use an injected fake clock
so they assert scheduling without sleeping.

---

## Using the dashboard

**Start a run.** Keywords (default "Software Engineer"), a time window in hours (default 24),
and a location (default "United States"). Enable **test mode** to cap it at 3 pages while you're
experimenting — a full run is 100 requests, a test run is 3.

**Watch it live.** Progress streams over SSE: pages fetched, requests made, cards seen, new jobs.
There's a Cancel button. A 15-minute run behind a spinner would be unusable, so results land as
they're parsed.

**Triage.** Three tabs:

| Tab | Shows |
|---|---|
| **Search** | Passing jobs from the current run that you haven't triaged |
| **Applied** | Everything you marked Applied, **across all runs** |
| **Not interested** | Everything you dismissed, **across all runs** |

The status tabs deliberately ignore which run a job came from — a job you applied to last week
shouldn't vanish because this morning's run replaced the view.

Click any column header to sort by it; click again to reverse.

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

**Data tab.** Database size on disk, row counts by verdict and status, and a
**Clear job data** button (two-step confirm, runs `VACUUM` so the file actually shrinks).

---

## Rate limiting

Three independent layers, all enforced in the sweep loop:

| Layer | Rule |
|---|---|
| Inter-request pacing | 6–12s jitter through a single global gate, measured from the **end** of the previous response |
| Per-run cap | 150 requests |
| Rolling 24h budget | 300 requests, counted from the `request_log` table so a restart can't reset it |

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
    per-run: 150
    per-rolling-day: 300
    test-mode-page-cap: 3
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

---

## What's not built yet

Deliberately out of scope for this version, with the seams left in place:

- **Detail fetching** — no descriptions are fetched. The schema columns and the partial-index
  queue are in place for it.
- **ATS adapters** (Greenhouse, Lever, Ashby, Workday) — the `JobSource` seam exists.
- **Relay/spam detection** — only the company-volume report works without descriptions.
- **Scheduling** — runs are manual only.

See [HANDOFF.md](HANDOFF.md) for why each of these was cut and what it would take to add them,
and [CODEMAP.md](CODEMAP.md) for a tour of the code.
