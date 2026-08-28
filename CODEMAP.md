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
             └─ test/                          mirrors main/java/, 119 tests
data/      the SQLite database file (gitignored, created on first boot)
```

There is no build step tying frontend and backend together in dev — Vite proxies `/api/*` to
`localhost:8080` (see `frontend/vite.config.ts`). In production you'd build the frontend
(`npm run build`) and serve `frontend/dist/` some other way; nothing in this repo does that yet.

The backend talks to exactly one external thing: LinkedIn's anonymous guest HTML endpoints. It
never talks to any other service, holds no credentials, and never authenticates.

---

## 2. The two request flows

Everything the system does is one of these two flows. Learn these and the rest of the code is
just detail.

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
`SweepShardsProperties` holds the configured metro shard list.

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
App.tsx                     top-level layout: run controls, jobs panel, filters, reports, data tab
api/client.ts                every fetch() call in the app lives here, one function per endpoint
types/api.ts                 hand-kept mirror of the backend DTOs — keep in sync manually
hooks/useRunStream.ts        the SSE subscription for live run progress
components/
  RunControls.tsx            the "start a run" form
  RunProgress.tsx            live counters + saturation warning + cancel button
  JobsPanel.tsx               the 3-tab job table, owns which tab/sort is active
  JobRow.tsx                  one row: title link, company, location, posted, salary, actions
  SortableHeader.tsx          clickable <th>, shared by the job table
  FiltersPanel.tsx            exclude words + blocked companies, list/add/delete
  CompanyVolumeReport.tsx     the relay-detection volume report
  DataPanel.tsx                DB size/stats + the two-step-confirm clear action
utils/
  sort.ts                     sortJobsBy() — mirrors JobSortOrder.java, plus per-column sort
  format.ts                   relative/absolute time, byte-size formatting
  runStatus.ts                human copy for each terminal run status
```

There is no client-side routing and no global state library — `App.tsx` holds the few pieces of
cross-component state (current run id, a `refreshToken` counter that other panels bump to
trigger a refetch) and passes them down as props.

---

## 5. The database

One SQLite file, `data/jobdash.db`, WAL mode. Schema lives in
`src/main/resources/db/migration/` (Flyway — `V1__init.sql` creates everything,
`V2__seed.sql` inserts the 8 default exclude words). **Never hand-edit a shipped migration** —
add a new `V3__...sql` instead.

```
job_listing        the results. See JobListingRepository above for the upsert rule.
sweep_run           one row per run, updated live while it's running
exclude_word        user's title-exclusion list, seeded with 8 defaults
company_blocklist    user's company-exclusion list
filter_state         single row, the version counter that drives re-evaluation
request_log          every LinkedIn request ever made — audit trail + rate-limiter budget source
circuit_state         single row, persisted breaker state
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

Current count: **119 tests** across 17 suites. Run `./mvnw test`.

---

## 7. Where do I…

| I want to… | Start here |
|---|---|
| Change what counts as a "senior" title to exclude | `V2__seed.sql` (defaults) or the Filters tab in the UI (runtime) — either way, matching logic is in `filter/FilterRuleSet.java` |
| Add a new field to the job table | `V1__init.sql`-style new migration → `domain/JobListing.java` → `store/JobListingRepository.java` mapping → `web/dto/JobResponse.java` → `frontend/src/types/api.ts` |
| Understand why a run stopped early | `sweep/SweepService.java`, the `switch` on `ResponseOutcome` in `runShard()` — cross-reference with `sweep_run.status` in the DB |
| Add a new REST endpoint | a method on the matching `web/*Controller.java`, backed by a repository method in `store/` |
| Change LinkedIn query parameters | `sweep/SweepQueryBuilder.java` only |
| Understand the rate limiter's math | `http/RateLimiter.java`, tests in `http/RateLimiterTest.java` use `FakeClock` to assert timing without waiting |
| Add a new metro shard | `application.yml`'s `sweep.shards` list — **verify the string against live LinkedIn first** (a wrong string returns the firehose, not an error) |
| Find what LinkedIn's HTML actually looks like | `src/test/resources/fixtures/*.html` — these are real saved responses |
| See what was deliberately left out | README's "What's not built yet" and HANDOFF.md §5 |
