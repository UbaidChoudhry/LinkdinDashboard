# Handoff

Everything decided while building this, and why. Read this before changing anything in the
sweep, filter, or HTTP layers — several decisions here look arbitrary but are load-bearing, and
a few were made *because* the obvious approach silently failed.

Written 2026-08-29. Status: all nine planned build tasks complete, 119 backend tests passing,
one live end-to-end run verified against real LinkedIn traffic.

---

## 1. The one thing to internalize

**In this codebase, the dominant failure mode is a bug that looks exactly like success.**

Six separate defects during the build shared that signature. None of them threw an exception,
logged an error, or failed a build. Each one just quietly produced an empty list or dropped a
value:

| What broke | What it looked like |
|---|---|
| `flyway-core` without the Boot 4 starter | App booted fine. No migration, no error, no database file. |
| `filter_verdict` stored as `PASS` not `pass` | Partial index predicate `= 'pass'` never matches. Detail queue permanently empty. |
| `filter_version < N` with `NULL` versions | SQL `<` against NULL is never true. New jobs never get filtered. |
| `\bC\+\+\b` as an exclude-word regex | Never matches. Exclude word silently does nothing. |
| Sweep never calling `FilterEngine` | Every row keeps a null verdict. Search tab permanently empty. |
| Controller passing 6 args to a 7-arg record | Convenience constructor absorbed it. `pageCap` silently discarded. |

The lesson that shaped the test suite: **assert on the positive outcome, not the absence of an
error.** "Did it throw?" proves nothing here. "Are there zero rows with a null verdict?" proves
something. When you add a feature, find the assertion that would fail if the feature quietly
did nothing.

---

## 2. Verified facts about LinkedIn's guest endpoint

These were measured against live traffic on 2026-08-28/29, not assumed. Several contradict what
the original project spec claimed. **Trust these over any document.**

### Endpoints in use

```
Search:  https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search
           ?keywords=…&location=…&f_TPR=r86400&sortBy=DD&start=0
Detail:  https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/{jobId}   (not currently used)
Public:  https://www.linkedin.com/jobs/view/{jobId}                        (link target only)
```

### Findings

- **Page size is 10, not 25.** `start=0` and `start=10` return disjoint ID sets. The pagination
  step is 10. Getting this wrong silently skips records.

- **The result cap is 1000 and it is hard.** `start >= 1000` returns **HTTP 400**. `start=999`
  returns HTTP 200 with a **26-byte end-of-results sentinel**, byte-exactly:
  ```
  <!DOCTYPE html>\n\n<!---->␠␠
  ```

- **A US-wide "Software Engineer" search over 24h saturates that cap.** `start=990` returned 10
  genuine results, meaning ≥1000 exist and everything past #1000 is invisible. A New York metro
  shard does *not* saturate. This is why sharding exists.

- **There is no JSON-LD.** `/jobs/view/{id}` returns zero `application/ld+json` blocks — checked
  on multiple postings. The original spec's "parse JSON-LD first, fall back to DOM" is inverted;
  DOM parsing is the only path, and structured `baseSalary` does not exist.

- **Filter facets are silently ignored.** `f_E=2,3` (entry + associate) returned **10/10
  identical job IDs** to the unfaceted query, still 8/10 senior titles. Assume every `f_*`
  parameter except `f_TPR` is inert until individually proven otherwise. **This is why all
  filtering is local** — it is not a preference.

- **A no-match keyword returns a firehose, not zero results.** Querying
  `zzzqqxnonexistentjobtitle999` returned 200 OK with 10 well-formed cards for *Cake Decorator*,
  *Domino's Production Associate*, and Japanese-language factory jobs. **A non-empty response is
  not a health check.** This is what the `IRRELEVANT` outcome guards against.

- **`sortBy=DD` is not a strict sort.** `start=0` returned jobs aged 11h, 8h, 2h, 12h
  interleaved. Don't rely on ordering.

- **Search cards carry no salary at all.** Salary appears only as prose inside the detail
  fragment description.

- **A default client User-Agent was *not* refused** — identical results to a browser UA. We send
  a realistic browser UA anyway, but it isn't the blocker the spec claimed.

- **Verified shard location strings** (all returned 10/10 relevant cards):
  `New York City Metropolitan Area`, `Los Angeles Metropolitan Area`,
  `Seattle, Washington, United States`, `Austin, Texas Metropolitan Area`.
  A *wrong* location string won't error — it returns the firehose. Verify any new shard string
  before trusting it.

**Fixtures for all of this are in `src/test/resources/fixtures/`.** `deep_999.html` and
`ny990.html` are the 26-byte sentinel (not corrupt files). `firehose_irrelevant.html` is the
Cake Decorator response — it contains 10 cards despite being the "bad query" capture, which is
exactly the point.

---

## 3. Architecture decisions

### SQLite, not PostgreSQL
The original spec called for Postgres. Rejected: this is one user, one machine, ~1000 rows per
run. Postgres means a daemon to install and run for no benefit. Verified SQLite handles
everything the schema needs — partial indexes with predicates, JSON, `group_concat`. The only
gap is `md5()`, which doesn't exist in SQLite; description hashing (when it arrives) must be
computed in Java, which is where the whitespace normalization belongs anyway.

**Tradeoff accepted:** if this ever becomes hosted or multi-user, SQLite → Postgres is a real
migration. For a local single-user tool that's a hypothetical not worth paying for now.

### Spring `JdbcClient`, not JPA
Every query is hand-written SQL: upserts with `on conflict`, partial-index-aware predicates,
conditional aggregation. JPA would add friction and hide exactly the details that matter here.

### On-demand runs, no scheduler
The spec described a 30-minute background poller with the 24h window computed locally. The user
chose on-demand. Consequence: **`f_TPR` is the actual freshness source** (hours × 3600), and
there is no accumulation of `first_seen_at` history across sweeps to fall back on.

### "Clear old results" without deleting anything
The user wanted a new run to clear the previous one's results. Deleting would destroy
`first_seen_at` (the dedup anchor), applied history, and re-evaluation ability. Instead:
`job_listing.last_seen_run_id` is stamped on every upsert, and the Search tab filters on
`last_seen_run_id = <latest run>`. Same user-visible behavior, no data loss, plus an "include
previous runs" toggle.

### Upsert keeps `first_seen_at` immutable
```sql
insert into job_listing (...) values (...)
on conflict(job_id) do update set
  last_seen_at = excluded.last_seen_at,
  last_seen_run_id = excluded.last_seen_run_id
```
`first_seen_at` is deliberately **absent** from the update list. This is asserted by
`JobListingRepositoryTest.firstSeenAtIsImmutableAcrossUpserts_lastSeenFieldsUpdate` — do not
break it. It diverges from the spec's `do nothing`, because a job re-found in a new run must
surface in the current view.

### Enums store lowercase
`FilterVerdict.toDb()` / `UserStatus.toDb()` write lowercase. **This is load-bearing.** The
partial index is:
```sql
create index job_detail_queue on job_listing (posted_at desc)
  where detail_fetched_at is null and filter_verdict = 'pass'
```
The column has no `collate nocase`. Writing `PASS` means the predicate never matches and the
detail-fetch queue is permanently, silently empty. Always go through `toDb()`.

### Filtering happens in the sweep, not on read
`SweepService` calls `filterEngine.evaluateNewRows()` immediately after each upsert batch. An
earlier version had the API layer doing this inside `GET /api/jobs` — which works, but puts a
write inside a GET and leaves every *other* reader (reports, the detail queue) seeing untriaged
rows. Filtering belongs to ingestion.

### Exclude-word regexes use lookarounds, not `\b`
```java
"(?<![A-Za-z0-9])" + Pattern.quote(word) + "(?![A-Za-z0-9])"
```
Java's `\b` fires only where exactly one adjacent character is a word character. For `C++`
followed by a space, both sides of the trailing boundary are non-word, so `\b` never fires and
`\bC\+\+\b` **silently never matches**. Verified. `Sr` is special-cased as
`(?<![A-Za-z0-9])sr\.?(?![A-Za-z0-9])` so it catches "Sr" and "Sr." but not "Sri".

Company matching is **exact**, not substring — blocking `Meta` must not exclude `Metabase`.

### Five response outcomes, not three
The spec described three. Measurement produced five (`ResponseOutcome`):

| Outcome | Detected by |
|---|---|
| `OK` | ≥1 parseable card |
| `END_OF_RESULTS` | the 26-byte sentinel, or <10 cards |
| `PAST_CAP` | HTTP 400 |
| `BLOCKED` | HTTP 429/999, **or** empty body when `start == 0` |
| `IRRELEVANT` | <30% of page-1 titles contain a keyword token |

**Empty-body handling is positional and this is the crux:** empty at `start == 0` is a block;
empty at `start > 0` is normal termination. Conflating them either hides an outage or fails
every healthy run.

### Circuit breaker must be a singleton
`CircuitBreaker` guards half-open with an in-memory `probeInFlight` flag. A second instance
breaks the "exactly one probe" guarantee and a blocked host gets a retry burst. The `@Bean`
methods in `HttpClientConfiguration` carry comments saying so. **Never add `@Scope("prototype")`
there.**

### Clearing job data never touches `request_log`
`DataRepository.clearJobResults()` deletes `job_listing` and `sweep_run` only. It deliberately
preserves:
- **`request_log`** — backs the rolling 24h budget. Deleting it would make "Clear job data" a
  way to reset the rate limiter, defeating the safety mechanism it enforces.
- **`exclude_word` / `company_blocklist`** — user config, meant to persist across sessions.
- **`circuit_state`** — live operational state, not user data.

---

## 4. Environment gotchas

- **Spring Boot 4 removed Flyway autoconfiguration** from `spring-boot-autoconfigure`. Adding
  `flyway-core` alone is a **silent no-op** — app boots, no migration, no error. You need
  `spring-boot-starter-flyway`. Boot 4 modularized autoconfig per technology; expect the same
  trap with other integrations.
- **The SQLite Flyway module is `flyway-database-nc-sqlite`**, not `flyway-database-sqlite`
  (which doesn't exist on Maven Central).
- **Spring Boot 4.x dropped the `.RELEASE` version suffix.** It's `4.1.1`.
- **Jackson 3** rejects `null` into a primitive `boolean` on record deserialization. Optional
  request flags must be boxed (`Boolean`), hence `testModeOrDefault()` helpers on
  `CreateRunRequest`.
- **`spring-boot-starter-webmvc-test` in Boot 4.1.1 ships no `@AutoConfigureMockMvc`.** Build
  `MockMvc` manually via `MockMvcBuilders.webAppContextSetup(context)` — see any test in
  `src/test/java/com/ubaid/jobdash/web/`.
- **The SQLite driver won't create its parent directory.** `data/.gitkeep` is committed with a
  gitignore exception so `data/` exists on a fresh clone.
- **`VACUUM` cannot run inside a transaction.** `DataRepository.vacuum()` is deliberately not
  `@Transactional` and must be called *after* `clearJobResults()` returns.
- **Shell:** zsh doesn't word-split unquoted variables. And `cd X && cmd & echo` parses as
  `(cd X && cmd) & echo` — the `cd` applies only to the backgrounded job. Both of these bit
  during `run.sh`.

---

## 5. What was cut, and what it takes to add

| Cut | Why | To add |
|---|---|---|
| **Salary** | ~~Cards carry none.~~ **Built 2026-09-06 — see §8.** | — |
| **Detail fetching** | Removing eager fetch was a user decision; clicks go straight to LinkedIn. | `detail_*` columns and the `job_detail_queue` partial index are in place. Build a worker draining that index newest-first. Respect the three-outcome rule: 404 → `gone`; 429/999/empty → leave `detail_fetched_at` null and open the breaker; success → populate. **Cache permanently — never re-fetch an `ok` row.** |
| **ATS adapters** | Deferred; needs a hand-maintained company→slug map. | Put them behind a `JobSource` seam. Greenhouse `boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true`, Lever `api.lever.co/v0/postings/{slug}?mode=json`, Ashby `api.ashbyhq.com/posting-api/job-board/{slug}`. These are free, public, documented, and fresher than LinkedIn. `job_listing.source` already exists. |
| **Relay/spam detection** | Its strongest signal is duplicate `description_hash`, which needs descriptions. Its other signal (apply-URL domain) is unavailable — the guest fragment's apply button carries **no href**. | Needs detail fetching first. Then hash `md5(lower(regexp_replace(description,'\s+',' ','g')))` **in Java**, and flag one body appearing under multiple company names. Suppress, never delete. Only the volume report works today. |
| **Scheduling** | User chose on-demand. | `SweepService.startRun()` is already async on a virtual thread. |

---

## 6. Live-traffic rules for whoever works on this next

1. **Never point tests at live LinkedIn.** Every test in this repo uses fixtures or stubs. Keep
   it that way.
2. **Use test mode when verifying by hand.** 3 requests instead of 100.
3. **Check the budget before running.** `GET /api/data/stats` reports `requestsLast24h` against
   the 300 cap. During this build, ~106 real requests were spent.
4. **A blocked run is a wait, not a bug.** If the breaker opens, leave it. Don't clear
   `circuit_state` to "fix" it, and don't clear `request_log` to reset the budget.
5. **If the parser starts returning nothing, check the markup before the code.** Compare a live
   response against the fixtures. LinkedIn changes markup without notice, and that failure is
   silent by nature.

---

## 7. State of the tree

Nothing has been committed — the entire project is untracked working-tree changes on `master`,
which has zero commits. First commit is yours to make.

`data/`, `logs/`, `.pids/`, `target/`, and `node_modules/` are gitignored. **Check `git status`
before the first commit** — `data/jobdash.db` may contain real scraped job data you don't want
in version control.

Test counts by suite are in [CODEMAP.md](CODEMAP.md). Run `./mvnw test` and expect **188
passing**.

---

## 8. Salary enrichment (added 2026-09-06)

**What it does.** During a sweep, right after `FilterEngine.evaluateNewRows()`, `SweepService`
calls `SalaryEnrichmentService.enrichRun(runId, location, cancelled)`. For every passing row
with no salary yet, it consults the `salary_estimate` cache keyed on
`(CompanyKey.of(company), TitleKey.of(title))`; on a miss it runs a source cascade and writes
`job_listing.salary_min/max/source` plus a cache row.

**The cascade** (`com.ubaid.jobdash.salary`, ordered in `SalaryEnrichmentService`, not by bean
order):
1. `LcaSalarySource` — local `lca_wage` table, populated by `./import-lca.sh <DOL xlsx>`.
   Company-specific. `SocMapper` turns the LinkedIn title into SOC occupation codes;
   `UsState.fromLocation` turns the location into a state; lookup falls back state → national.
   Maps `wage_p25 → salaryMin`, `wage_p75 → salaryMax`.
2. `AdzunaSalarySource` — Adzuna API, title + location estimate. Blank key ⇒ skipped.
3. `H1bApiSalarySource` — h1bapi.com, company-specific. **Endpoint/field mapping was written
   from public docs without a key to verify — see the `NOTE:` in that class.** Blank key ⇒ skipped.

**Decisions worth knowing:**
- **Inline, not a post-run pass** — a deliberate product call. `enrichRun` re-queries
  `findPassingWithoutSalaryByRun` on every page; enriched rows drop out (their `salary_source`
  is set), but rows that *missed* keep a null `salary_source` and a `source='none'` cache row,
  so they're re-checked cheaply (cache hit, no network) once per remaining page. Acceptable —
  all local SQLite — but if you make enrichment heavier, move it to end-of-run.
- **`SalaryEnrichmentService` never throws.** Per-row `catch (Exception)`, per-source
  `catch (RuntimeException)`. A salary failure must never abort a sweep. Assert this stays true.
- **Two rate-limit layers on the external APIs** (`SalaryRateLimiter`, a singleton like
  `RateLimiter` — the pacing gate is an instance field): a shared 1s pacing gate, and a
  per-source rolling-24h cap counted from the `external_request_log` table (survives restart).
  This is **separate from** the LinkedIn `request_log` / `RateLimiter` — never conflate them.
- **3-year staleness** (`salary.max-data-age`, default 1095d): a source result whose `dataDate`
  is older than that is dropped and the cascade continues. LCA rows older than the cutoff are
  dropped at *import* time too.
- **90-day cache TTL** (`salary.cache-ttl`): a fresh cache row (any source, including `'none'`)
  short-circuits all lookups — this is the "only check where no record exists" rule.
- **`XlsxStreamReader` is dependency-free** — hand-rolled StAX + `java.util.zip`, no Apache POI.
  The DOL file's `sheet1.xml` is ~500 MB uncompressed; it must be streamed. Real column layout
  notes are in that class and `LcaImportService`.
- **Keys live in `.env`** (gitignored), sourced by `run.sh`. `application.yml` reads
  `${ADZUNA_APP_ID:}` etc. `SALARY_SETUP.md` is the user-facing guide.
- The two-bucket sort (`JobSortOrder`, `utils/sort.ts`) was already built and correct; only the
  frontend **Min salary** input (`filterByMinSalary` in `utils/sort.ts`) is new. It hides rows
  with a *known* salary below the threshold and never hides unknown-salary rows.
