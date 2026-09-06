# Code Map

A tour of this repo for someone who has never seen it. Read top to bottom the first time;
after that, use the "where do I…" table at the end.

---

## 1. The shape of the system

```
frontend/  React + TypeScript dashboard (Vite dev server, :5173)
src/       Spring Boot backend (Java 21, :8080)
             ├─ main/java/com/ubaid/jobdash/   application code, by package (below)
             ├─ main/resources/                application.yml, Flyway migrations
             └─ test/                          mirrors main/java/, 305 tests
data/      the SQLite database file (gitignored, created on first boot)
```

There is no build step tying frontend and backend together in dev — Vite proxies `/api/*` to
`localhost:8080` (see `frontend/vite.config.ts`). In production you'd build the frontend
(`npm run build`) and serve `frontend/dist/` some other way; nothing in this repo does that yet.

The backend talks to four kinds of external thing, and they are deliberately kept apart:
LinkedIn's anonymous guest HTML endpoints, the ATS job boards (Greenhouse / Lever / Workday),
the salary APIs, and the local `claude` CLI. It never authenticates to any of them — the ATS
boards and LinkedIn are public, the salary keys are optional, and the CLI uses your own Claude
subscription. **Each has its own rate limiter and its own budget; conflating them is a bug**
(see HANDOFF.md §8 and §9).

---

## 2. The two request flows

Everything the system does is one of these two flows. Learn these and the rest of the code is
just detail.

**Since 2026-09-07 a run picks its sources**, and `RunOrchestrator` is the fork in the road:

```
POST /api/runs { sources: [...] }            [RunController -> RunOrchestrator]
   sources == ["linkedin"]  → SweepService     (Flow A below — unchanged)
   otherwise                → AtsSweepService  (Flow A' — one pass per enabled company)
                              then, as the LAST step, ResumeMatchService (the AI scan)
```
LinkedIn cannot be combined with the ATS sources: its guest search returns no job description
(HANDOFF.md §2), so the scan would have nothing to read. The API rejects the combination with a
400. Flow A below describes the LinkedIn path and is unchanged by any of this.

### Flow A — starting a sweep (write path, hits LinkedIn)

```
User clicks "Start run" in RunControls.tsx
  → POST /api/runs                                    [RunController]
      guards: is a run already in flight? is the circuit breaker open?
      → SweepService.startRun()                        [sweep/SweepService.java]
          creates a sweep_run row, status='running'
          spawns a virtual thread running SweepService.run(runId, request)
      ← returns { runId } immediately (HTTP 201)

  Meanwhile, on the virtual thread, SweepService.run() loops:
    for each shard (or once, if sharding is off):
      loop, start = 0, 10, 20, …
        SweepQueryBuilder.buildUri(keywords, location, hours, start)
          → https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search
                ?keywords=…&location=…&f_TPR=r{hours*3600}&sortBy=DD&start={start}
        PacedHttpClient.fetch(uri, start, keywords)      [http/PacedHttpClient.java]
          RateLimiter.checkBudget()   — 3 layers: pacing gate, per-run cap, rolling 24h budget
          CircuitBreaker.beforeCall() — refuses if OPEN, admits exactly 1 probe if HALF_OPEN
          → real HTTP GET to LinkedIn
          RequestBudgetStore.record() — logged to request_log (this IS the rolling-budget data)
          ResponseOutcomeDetector.classify() → one of OK / END_OF_RESULTS / PAST_CAP / BLOCKED / IRRELEVANT
        SweepService switches on the outcome:
          OK              → CardParser.parse(html) → JobListingRepository.upsertAll(...)
                             → FilterEngine.evaluateNewRows()  (stamps pass/reject on what was just written)
                             → start += 10, keep looping
          END_OF_RESULTS  → stop this shard, move to next (or finish if last)
          PAST_CAP        → stop this shard, mark saturated=true
          BLOCKED         → stop the WHOLE run, status='blocked'
          IRRELEVANT      → stop the WHOLE run, status='failed', nothing from this page is stored
        after each page: SweepRunRepository.updateProgress(...) persists counters,
                          an in-memory ConcurrentHashMap<runId, SweepProgress> is updated too

  Meanwhile, the browser is watching:
    GET /api/runs/{id}/stream  (Server-Sent Events)      [RunController]
      polls the in-memory progress map every ~750ms, pushes each snapshot to the client
      → RunProgress.tsx renders live counters until a terminal status arrives
```

**If you're debugging "why didn't my job show up," this is the flow to trace.** Check, in order:
did the HTTP request succeed → did the parser produce a card → did the upsert insert a new row
→ did the filter engine mark it `pass` → is it in the current run.

### Flow A' — an ATS run (write path, hits Greenhouse / Lever / Workday)

```
RunOrchestrator → AtsSweepService.run(runId, request)      [sweep/AtsSweepService.java]
  companies = AtsCompanyRepository.findForRun(sources, ats.max-companies-per-run)
      ← ONLY rows with enabled=1 AND status<>'dead'
      ← if empty, the run finishes 'no_sources' (a config problem, not a successful empty run)
  for each company:
      Workday with no cached site? WorkdaySiteResolver reads its robots.txt, then upsertSite()
      JobSource.fetch(SourceQuery)  →  sealed SourceFetchResult
        Ok             → upsertAll(...) → FilterEngine.evaluateNewRows()
                          → SalaryEnrichmentService.enrichRun(...)  → recordSuccess()
        DeadSlug       → recordFailure(): after ats.dead-slug-threshold strikes the row goes
                          status='dead', enabled=0 and is never visited again
        RateLimited    → stop the whole run, status='budget_exhausted'
        TransportError → record it and CONTINUE — one unreachable company must not abort 150
      publish progress (companiesDone / companiesTotal)

  then, last: status='scanning' → ResumeMatchService.scan(runId, resumeId, cancelled)
      jobs WITH a description, minus anything already cached in ai_match
      → batches of ai.batch-size, ai.concurrency `claude` processes at once
      → ai_match rows → the Recommended / Not recommended buckets in the UI
      (no resume uploaded? the scan is skipped and the run still finishes 'ok')
```

### Flow B — reading the dashboard (read path, never touches LinkedIn)

```
User opens a tab / loads the page
  → GET /api/jobs?tab=search&includePreviousRuns=false     [JobController]
      "search"          → rows from the CURRENT run only, filter_verdict='pass', user_status=null
      "applied"         → rows with user_status='applied', ACROSS ALL RUNS
      "not_interested"  → rows with user_status='not_interested', ACROSS ALL RUNS
      sorted by JobSortOrder.DEFAULT (salary desc if present, else posted_at desc)
      → JobResponse[] (DTO, not the raw domain object)
  ← JobsPanel.tsx renders the table; SortableHeader.tsx re-sorts client-side on click

User clicks "Applied" on a row
  → POST /api/jobs/{id}/status  { status: "applied" }      [JobController]
      → JobListingRepository.setUserStatus(...)
  ← row disappears from Search, will appear in Applied on next load
```

Also read-only, same shape: `GET /api/filters/words`, `GET /api/filters/companies`,
`GET /api/reports/company-volume`, `GET /api/data/stats`.

---

## 3. Backend package tour

`src/main/java/com/ubaid/jobdash/`

### `domain/` — plain data, no logic
`JobListing`, `SweepRun` (the two rows-as-objects), `FilterVerdict` / `UserStatus` (enums —
**note `toDb()`/`fromDb()` store lowercase text**, this is load-bearing, see HANDOFF.md §3),
`Timestamps` (the one place `Instant` ↔ ISO-8601 text conversion happens; SQLite has no native
datetime type).

### `source/linkedin/` — turning HTML into data
`CardParser.parse(html) -> List<JobCard>` — jsoup selectors over a search-results page. This is
the **only place LinkedIn's HTML structure is understood**. If LinkedIn changes their markup,
this is what breaks, and `CardParserTest` (run against saved fixtures, no network) is what tells
you.

### `http/` — talking to LinkedIn safely
This package is the safety layer and nothing else — it does not know what a "job" is.

| File | Job |
|---|---|
| `RateLimiter` | 3 layers: inter-request pacing (6-12s, global single-permit gate), per-run request cap, rolling-24h budget (reads `RequestBudgetStore`, never an in-memory count — must survive a restart) |
| `CircuitBreaker` | CLOSED → OPEN → HALF_OPEN state machine. Trips immediately on 429/999, after 2 soft failures otherwise. **Must be a Spring singleton** — half-open's "exactly one probe" guarantee is an in-memory flag |
| `ResponseOutcomeDetector` | Classifies a raw HTTP response into one of 5 `ResponseOutcome` values. The positional empty-body rule (start=0 → BLOCKED, start>0 → END_OF_RESULTS) lives here |
| `PacedHttpClient` | Wires the three above together into one `fetch(uri, start, keyword) -> FetchResult` call. Everything else in the app calls this, never raw `HttpClient` |
| `SweepProperties` | typed binding of the `sweep:` block in `application.yml` |
| `HttpClientConfiguration` | the `@Bean` wiring — **read the comments here before touching singleton scope** |

`FetchResult` is a sealed interface: `Completed` / `BlockedByRunCap` / `BlockedByDailyBudget` /
`BlockedByCircuit` / `TransportError`. Switch exhaustively on it; that's the point of it being
sealed.

### `filter/` — deciding pass/reject
`FilterEngine` (the `@Service`, called from `SweepService` right after every upsert) builds a
`FilterRuleSet` from the current exclude-word list and company blocklist, evaluates rows, and
handles re-evaluation when a list changes (`bumpVersionAndReevaluate()` — this is what makes
editing a filter retroactive). Whole-word title matching uses lookaround regex, **not `\b`** —
see HANDOFF.md §3 for why `\b` silently fails on words like `C++`.

### `sweep/` — the pagination loop
`SweepService` is the orchestrator described in Flow A above. `SweepQueryBuilder` builds the
LinkedIn search URL (and is the one place that would need to change if LinkedIn's query
parameters ever change). `SweepRunRequest` / `SweepProgress` are the input/output shapes.
`SweepShardsProperties` holds the configured metro shard list. After each page's filter pass it
calls `salary.SalaryEnrichmentService.enrichRun(...)` inline (see below).

### `salary/` — attaching a pay range to each job
Runs inside the sweep, right after filtering. `SalaryEnrichmentService` walks the run's passing,
salary-less rows; for each it checks the `salary_estimate` cache (key: normalized
`CompanyKey` + `TitleKey`, 90-day TTL) and on a miss runs a source cascade, writing
`job_listing.salary_min/max/source`.

| File | Job |
|---|---|
| `SalarySource` | the seam: `Optional<SalaryResult> lookup(SalaryLookup)`. Three impls, tried in this order: |
| `LcaSalarySource` | local `lca_wage` table (US DOL H-1B LCA disclosure data). Company-specific. Uses `SocMapper` (title → SOC code) and `UsState` (location → state). Lookup is 4-tier: exact+state, exact+national, **word-boundary prefix**+state, prefix+national — so "Amazon" reaches `amazon com services` without `meta` reaching `metabase`. Returns the matched employer so the UI can show it |
| `AdzunaSalarySource` | Adzuna API — title+location estimate. Skipped if no key |
| `H1bApiSalarySource` | h1bapi.com — company-specific H-1B wages. Skipped if no key. **Request shape unverified against a live key — see its `NOTE:`** |
| `SalaryRateLimiter` | **singleton** (pacing gate is an instance field, like `http/RateLimiter`). Shared 1s pacing gate + per-source rolling-24h cap counted from `external_request_log`. Separate from the LinkedIn budget |
| `XlsxStreamReader` | dependency-free streaming `.xlsx` reader (StAX + `java.util.zip`, no POI). The DOL file's sheet is ~500 MB uncompressed |
| `LcaImportService` / `LcaImportRunner` | `importFrom` = one file: parse → keep `Certified` → annualize wage → drop >3yr-old rows → aggregate percentiles into `lca_wage`. `importAll` = a whole directory, deleting each spreadsheet that actually contributed rows. The runner is a one-shot batch job (no web server) triggered by `./import-lca.sh [dir]`, property `salary.lca.import-path` |
| `SalaryProperties` / `SalaryConfiguration` | the `salary:` config block; beans `salaryRateLimiter`, `salaryHttpClient` |
| `CompanyKey` / `TitleKey` / `SocMapper` / `UsState` | pure normalization helpers |

`SalaryEnrichmentService` **never throws** — a salary failure must not abort a sweep. Keys come
from `.env` (sourced by `run.sh`); see `SALARY_SETUP.md`.

### `source/` — the job-source seam (added 2026-09-07)
`JobSource.fetch(SourceQuery) -> SourceFetchResult` is the seam every board plugs into, and
`SourceFetchResult` is sealed (`Ok` / `DeadSlug` / `RateLimited` / `TransportError`) so callers
must switch exhaustively. Implementations **never throw** — same discipline as `SalarySource`.

| Package | Job |
|---|---|
| `source/linkedin/` | the original HTML card parser (unchanged) |
| `source/greenhouse/` | one request returns the whole board; `content` is entity-escaped HTML |
| `source/lever/` | one request returns the whole board; ids are UUIDs, `createdAt` is epoch ms. **HTTP 200 + `[]` is a live board with no jobs, NOT a dead slug** |
| `source/workday/` | two-phase: a server-side-filtered search page, then one detail request per job for the description and real date. `WorkdaySiteResolver` discovers the site id from the tenant's `robots.txt` |
| `source/ats/` | `AtsRateLimiter` (pacing + per-source daily cap off `external_request_log` — **never** LinkedIn's `request_log`), `AtsProperties`, and `SlugCatalogImportService` / `SlugImportRunner` behind `./import-slugs.sh` |

`sweep/OrphanedRunReaper` runs once at startup and closes out any run left `running` by a killed
process. Without it a single interrupted run blocks every future run **and** cannot be cancelled
(cancel only works for runs in the current process's registry) — see HANDOFF.md §9.

### `resume/` — turning an upload into text
`ResumeService` stores the original under `data/resumes/` and the extracted text in the `resume`
table; `ResumeTextExtractor` handles PDF (Apache PDFBox 3 — `Loader.loadPDF`, the 2.x
`PDDocument.load` is gone) and .txt/.md, and rejects an image-only PDF with a message saying so.

### `ai/` — resume ↔ job matching via the Claude CLI
`ResumeMatchService` is the orchestrator: it takes the run's passing jobs **that have a
description**, drops the ones already cached in `ai_match`, batches the rest
(`ai.batch-size`), and runs up to `ai.concurrency` `claude` processes at once.
`MatchPromptBuilder` renders the batch (stripping HTML from both Greenhouse's escaped and
Lever's raw form) and owns the `--json-schema`. `ClaudeCliClient` runs the binary — prompt on
**stdin**, stdout and stderr drained on separate threads — and returns a sealed
`ClaudeCliResult`. **Neither class ever throws**; an AI failure must not fail a run.

`ScanProgress` / `ScanProgressListener` report the scan live: snapshots ride the existing SSE
stream into `RunProgress.tsx`, and the `jobdash.ai.scan` logger writes `logs/ai-scan.log` for
`tail -f` (configured in `logback-spring.xml`). See HANDOFF.md §9.

### `store/` — all SQL lives here
One `@Repository` per table, all built on Spring's `JdbcClient` (hand-written SQL, no JPA — see
HANDOFF.md §3 for why). If you need a new query, it goes in the matching repository, not
scattered elsewhere.

| Repository | Table |
|---|---|
| `JobListingRepository` | `job_listing` — **the upsert here is the one method to understand first**: `first_seen_at` is deliberately excluded from the `on conflict do update` clause |
| `SweepRunRepository` | `sweep_run` |
| `ExcludeWordRepository`, `CompanyBlocklistRepository` | the two user-editable filter lists |
| `FilterStateRepository` | the single-row filter version counter |
| `RequestLogRepository` | `request_log` — **this backs the rate limiter's daily budget**, see below |
| `CircuitStateRepository` | `circuit_state` — persisted breaker state |
| `DataRepository` | aggregate stats + the scoped `clearJobResults()` |
| `DatabaseFileLocator` | resolves the SQLite file path from `spring.datasource.url`, reports its size on disk (main + `-wal` + `-shm`) |
| `SalaryEstimateRepository` | `salary_estimate` — the per-(company,title) salary cache (90-day TTL; a `source='none'` row means "looked, found nothing") |
| `LcaWageRepository` | `lca_wage` — aggregated DOL LCA wage percentiles, keyed `(employer_key, soc_code, state)` (`state=''` is the national roll-up), plus `employer_display` (the raw legal name, shown in the UI). **`lookup()`'s prefix predicate is word-boundary only — see HANDOFF.md §8 before touching it** |
| `ExternalRequestLogRepository` | `external_request_log` — backs `SalaryRateLimiter`'s and `AtsRateLimiter`'s per-source daily caps; **not** the LinkedIn budget |
| `AtsCompanyRepository` | `ats_company` — the company catalog. `findForRun` returns only enabled, non-dead rows; `recordFailure` retires a slug after `ats.dead-slug-threshold` consecutive 404s |
| `ResumeRepository` | `resume` — uploaded resumes; `setDefault` keeps exactly one default row |
| `AiMatchRepository` | `ai_match` — cached AI verdicts keyed `(job_id, resume_id)` |

`store/adapter/` — `JdbcRequestBudgetStore` and `JdbcCircuitStateStore` bridge the `http`
package's storage-agnostic interfaces (`RequestBudgetStore`, `CircuitStateStore`) onto the real
repositories. This split exists because the `http` package was built in parallel with the
`store` package and neither should import the other's concrete types.

### `web/` — HTTP in, JSON out
One `@RestController` per resource area: `RunController`, `JobController`, `FilterController`,
`ReportController`, `DataController`. Each is thin — it translates HTTP to a service/repository
call and back, no business logic. `web/dto/` holds every request/response shape; **the frontend's
`frontend/src/types/api.ts` is a hand-kept mirror of these** — if you change a DTO, update both.

`GlobalExceptionHandler` + `ApiException` — the only way an error reaches the client is
`{"message": "..."}` with a real status code, never a stack trace. Throw `ApiException` with a
message a user could act on.

`JobSortOrder` — the two-bucket salary/recency comparator. Salary is always null today (see
README's "What's not built yet"), so every row currently falls in the second bucket; the
comparator is already correct for when that changes.

---

## 4. Frontend tour

`frontend/src/`

```
App.tsx                     top-level tab shell: Search / Results / Filters / Data. Panels stay
                            mounted and toggle via `hidden` (so their state survives a switch),
                            hence the `[hidden]{display:none!important}` rule in App.css
api/client.ts                every fetch() call in the app lives here, one function per endpoint
types/api.ts                 hand-kept mirror of the backend DTOs — keep in sync manually
hooks/useRunStream.ts        the SSE subscription for live run progress
components/
  RunControls.tsx            the "start a run" form (+ source picker and resume picker)
  SourceSelect.tsx            multi-select dropdown for a run's sources; enforces LinkedIn's
                              exclusivity AT SELECTION TIME, so an invalid combination can't
                              even be assembled
  SourcesPanel.tsx            the ATS company catalog: paginated + searchable (it can hold
                              >15,000 rows), enable toggles, dead-slug state, manual add
  ResumesPanel.tsx            upload / set default / delete, and preview the exact extracted
                              text the model will be given
  RunProgress.tsx            live counters + saturation warning + cancel button
  JobsPanel.tsx               the 3-tab job table, owns which tab/sort is active + the Min salary filter
  JobRow.tsx                  one row: title link, company, location, posted, salary (+ source label), actions
  SortableHeader.tsx          clickable <th>, shared by the job table
  FiltersPanel.tsx            exclude words + blocked companies, list/add/delete
  CompanyVolumeReport.tsx     the relay-detection volume report
  DataPanel.tsx                DB size/stats + the two-step-confirm clear action
  CompanyGroupRow.tsx         collapsed summary row for a company with 2+ jobs (Group by company)
utils/
  sort.ts                     sortJobsBy() — mirrors JobSortOrder.java, plus per-column sort + filterByMinSalary()
  group.ts                    groupByCompany() — buckets an ALREADY-SORTED list, preserving order
                              between and within groups, so the active sort keeps working untouched
  format.ts                   relative/absolute time, byte-size formatting
  runStatus.ts                human copy for each terminal run status
```

There is no client-side routing and no global state library — `App.tsx` holds the few pieces of
cross-component state (current run id, a `refreshToken` counter that other panels bump to
trigger a refetch) and passes them down as props.

### The layout "fill chain" — read before touching App.css

The results table is sized to absorb whatever vertical space is left over, so the dashboard fits
one screen without page scrolling. That depends on an unbroken chain, and **removing any single
link silently brings the page scrollbar back**:

```
#root            height: 100svh   (a DEFINITE height - min-height leaves it content-sized
                 overflow: auto    and "leftover space" undefined); overflow is the
                                   short-window fallback
  .app-shell     flex: 1; min-height: 0
  .app-main      flex: 1; min-height: 0
  .tab-panel     flex: 1; min-height: 0
  .jobs-panel    flex: 1; min-height: 0
  .job-table-scroll  flex: 1; min-height: 320px; overflow: auto   ← absorbs the remainder
```

`min-height: 0` is the load-bearing half: a flex item defaults to `min-height: auto` (its content
size) and refuses to shrink, pushing overflow onto the page instead of into the table's own
scroll area. The `320px` floor at the end is deliberate — on a window too short for the chrome
plus that floor, the page scrolls, which is the accepted small-screen behaviour.

Width is the mirror image: `#root` and `.app-shell` are uncapped (full monitor), and only
`.tab-panel:not(.tab-panel-wide)` re-caps the non-table panels to a readable 1200px.

---

## 5. The database

One SQLite file, `data/jobdash.db`, WAL mode. Schema lives in
`src/main/resources/db/migration/` (Flyway — `V1__init.sql` creates everything,
`V2__seed.sql` inserts the 8 default exclude words, `V3__salary.sql` adds the salary tables,
`V4__lca_employer_display.sql` adds the matched-employer columns).
**Never hand-edit a shipped migration** — add a new `V5__...sql` instead.

```
job_listing        the results. See JobListingRepository above for the upsert rule.
sweep_run           one row per run, updated live while it's running (+ sources, resume_id,
                     companies_done/total from V8)
exclude_word        user's title-exclusion list, seeded with 8 defaults
company_blocklist    user's company-exclusion list
filter_state         single row, the version counter that drives re-evaluation
request_log          every LinkedIn request ever made — audit trail + rate-limiter budget source
circuit_state         single row, persisted breaker state
salary_estimate      per-(company,title) salary cache, 90-day TTL           (V3)
lca_wage             aggregated DOL LCA wage percentiles by employer/SOC/state (V3)
external_request_log  audit + daily-cap source for the salary AND ATS APIs (NOT LinkedIn's) (V3)
ats_company          the ATS slug catalog: enabled/status/dead tracking, Workday host+site (V6)
resume               uploaded resumes + their extracted text                        (V7)
ai_match             cached AI verdicts, keyed (job_id, resume_id)                  (V7)
```

Two indexes worth knowing about:
- `job_detail_queue` — a **partial** index (`where detail_fetched_at is null and
  filter_verdict = 'pass'`). Nothing reads it yet (detail fetching isn't built), but it's the
  seam for it, and it's *why* verdicts must be stored lowercase.
- `job_browse` — supports the tab queries in `JobController`.

---

## 6. Tests

Test packages mirror `main/java` exactly — if you're looking for tests of
`sweep/SweepService.java`, they're in `test/.../sweep/SweepServiceTest.java`.

**No test ever makes a live network call.** The pattern to copy:
- HTTP layer tests use `StubHttpClient` / a hand-built `HttpClient` subclass, never real sockets.
- `RateLimiter` / `CircuitBreaker` tests use `FakeClock` + `FakeSleeper` (advances the clock
  instead of actually waiting) — see `http/support/`.
- Repository and API tests spin up a **real, temporary SQLite file** per test (`@TempDir`) and
  run genuine Flyway migrations against it — see `store/AbstractStoreTest.java` and
  `web/JobDashApiTest.java` / `web/DataControllerTest.java`. This is deliberate: it catches real
  SQL/schema bugs that a mocked datasource would hide (several of the bugs in HANDOFF.md §1 were
  caught exactly this way).
- Parser tests run against saved HTML in `src/test/resources/fixtures/` — see that directory's
  file names for what each fixture represents (the two 26-byte files are the real end-of-results
  sentinel, not corrupt captures).

Current count: **305 tests**. Run `./mvnw test`. Salary tests follow the same rules — the
Adzuna / h1bapi sources are driven through `StubHttpClient`, `SalaryRateLimiter` through
`FakeClock`/`FakeSleeper`, and `LcaImportServiceTest` generates a tiny `.xlsx` in memory.

---

## 7. Where do I…

| I want to… | Start here |
|---|---|
| Change what counts as a "senior" title to exclude | `V2__seed.sql` (defaults) or the Filters tab in the UI (runtime) — either way, matching logic is in `filter/FilterRuleSet.java` |
| Add a new field to the job table | `V1__init.sql`-style new migration → `domain/JobListing.java` → `store/JobListingRepository.java` mapping → `web/dto/JobResponse.java` → `frontend/src/types/api.ts` |
| Understand why a run stopped early | `sweep/SweepService.java`, the `switch` on `ResponseOutcome` in `runShard()` — cross-reference with `sweep_run.status` in the DB |
| Add a new REST endpoint | a method on the matching `web/*Controller.java`, backed by a repository method in `store/` |
| Change LinkedIn query parameters | `sweep/SweepQueryBuilder.java` only |
| Work on salary data / add a salary source | `salary/` package, `SalaryEnrichmentService` is the orchestrator; `SALARY_SETUP.md` + HANDOFF.md §8 |
| Import DOL LCA wage data | `./import-lca.sh <xlsx>` → `salary/LcaImportService.java` |
| Understand the rate limiter's math | `http/RateLimiter.java`, tests in `http/RateLimiterTest.java` use `FakeClock` to assert timing without waiting |
| Add an ATS company | the Sources tab, or `./import-slugs.sh` for a whole catalog — **imported rows arrive disabled**, see HANDOFF.md §9 |
| Add a NEW ATS platform (the 4th, 5th, …) | implement `source/JobSource`, add the name to `RunController`'s valid set and `AtsProperties.DailyCap`, and teach `SlugCatalogImportService` its key. The seam is the whole point — nothing in `sweep/` should need to change |
| Work out why a company returns nothing | its `ats_company` row: `status`, `consecutive_failures`, `last_checked_at`, and for Workday whether `site` ever resolved |
| Change how jobs are scored against a resume | `ai/MatchPromptBuilder` (the prompt + JSON schema); `ai/ResumeMatchService` (batching/caching) |
| Understand the Claude CLI invocation | `ai/ClaudeCliClient` + HANDOFF.md §9 — **prompt on stdin, read `structured_output`** |
| Add a new metro shard | `application.yml`'s `sweep.shards` list — **verify the string against live LinkedIn first** (a wrong string returns the firehose, not an error) |
| Find what LinkedIn's HTML actually looks like | `src/test/resources/fixtures/*.html` — these are real saved responses |
| See what was deliberately left out | README's "What's not built yet" and HANDOFF.md §5 |
| Set up salary API keys | `SALARY_SETUP.md` — copy `.env.example` → `.env` |
