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
Detail:  https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/{jobId}   (detail phase, §10)
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

> **Updated 2026-09-07 (§9):** the conflict target is now `on conflict(source, source_job_id)`,
> not `job_id` — V5 made `job_id` a surrogate key so non-numeric ATS ids fit. The rule above is
> unchanged: `first_seen_at` is still absent from the update list, and the same test guards it.

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
| **Detail fetching** | ~~Removing eager fetch was a user decision.~~ **Built 2026-09-09 — see §10.** A capped post-collection phase, not an eager per-card fetch; clicks still go straight to LinkedIn. | — |
| **ATS adapters** | ~~Deferred.~~ **Built 2026-09-07 — see §9.** Greenhouse, Lever and Workday are implemented behind the `JobSource` seam; the company→slug map is the `ats_company` catalog. Ashby and the other 21 platforms in the catalog file remain unimplemented. | — |
| **Relay/spam detection** | Its strongest signal is duplicate `description_hash`, which needs descriptions. Its other signal (apply-URL domain) is unavailable — the guest fragment's apply button carries **no href**. | `description_hash` is now written for every fetched LinkedIn row (`DetailParser.hashOf`, §10). What's left: flag one hash appearing under multiple company names. Suppress, never delete. Only the volume report works today. |
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

Test counts by suite are in [CODEMAP.md](CODEMAP.md). Run `./mvnw test` and expect **542
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
- **Three rate-limit layers on the external APIs** (`SalaryRateLimiter`, a singleton like
  `RateLimiter` — the pacing gate is an instance field): a shared 1s pacing gate, a per-source
  rolling-**24h** cap, and a per-source rolling-**30-day** cap, both counted from the
  `external_request_log` table (survives restart). This is **separate from** the LinkedIn
  `request_log` / `RateLimiter` — never conflate them.
- **Provider free-tier limits are load-bearing config, verified 2026-09-06.** The first cut of
  this feature shipped `daily-cap.h1bapi: 100` and `daily-cap.adzuna: 200` — both numbers were
  guessed from search snippets, never checked against the providers' pricing pages, and both
  were wrong in a way that would have silently burned the user's free quota:

  | Provider | Real free tier | Enforced by |
  |---|---|---|
  | h1bapi.com | **20 requests/day**, last 2 fiscal years, **no API key required** | `daily-cap.h1bapi: 20` |
  | Adzuna | ~**1,000 calls/month** (a *monthly* allowance) | `monthly-cap.adzuna: 900` + `daily-cap.adzuna: 40` |

  Two lessons baked into the code: **(a)** a daily cap cannot protect a *monthly* quota — 30 days
  of "safe" daily usage still blows it, which is why the 30-day window exists (a cap of `<= 0`
  means "no monthly limit"); **(b)** an absent API key is not the same as a disabled source —
  `H1bApiSalarySource` calls the endpoint either way and only attaches `X-API-Key` when one is
  set, because short-circuiting on a blank key silently disabled the free tier for everyone who
  never signed up. `H1bApiSalarySourceTest.blankKeyStillCallsTheApiButSendsNoAuthHeader` and
  `SalaryRateLimiterTest.monthlyCapBlocksEvenWhenEveryIndividualDayIsUnderTheDailyCap` are the
  regression guards. **Before changing any cap, open the provider's pricing page.**
- **h1bapi is a long-shot fallback, not a real source.** 20 requests/day is exhausted almost
  immediately on any real run. The local LCA dataset is the workhorse for company-specific pay;
  h1bapi sits last in the cascade for a reason.
- **3-year staleness** (`salary.max-data-age`, default 1095d): a source result whose `dataDate`
  is older than that is dropped and the cascade continues. LCA rows older than the cutoff are
  dropped at *import* time too.
- **90-day cache TTL** (`salary.cache-ttl`): a fresh cache row (any source, including `'none'`)
  short-circuits all lookups — this is the "only check where no record exists" rule.
- **`XlsxStreamReader` is dependency-free** — hand-rolled StAX + `java.util.zip`, no Apache POI.
  The DOL file's `sheet1.xml` is ~500 MB uncompressed; it must be streamed. Real column layout
  notes are in that class and `LcaImportService`. **Verified end-to-end against the real
  `LCA_Disclosure_Data_FY2024_Q4.xlsx`**: 598,831 rows read → 118,121 kept → 100,372 groups in
  ~11 seconds.
- **`./import-lca.sh` imports a whole directory and deletes as it goes** (default `data/lca`,
  `--keep` opts out). Deletion policy lives in `LcaImportService.importAll`, not the shell, so
  it is testable — and it is deliberately conservative: **a file is deleted only when the import
  both succeeded and kept ≥1 row.** A file that parses to zero rows is *kept*, because that
  signals a changed column layout far more often than a genuinely empty quarter, and the 80 MB
  download is the only evidence for diagnosing it. Failures never abort the batch.
  `LcaImportServiceTest.neverDeletesAFileThatContributedNoRows` guards this.
- **The import runs with `--spring.main.web-application-type=none`.** It is a batch job; binding
  Tomcat was pointless and collided with a running backend. The script still refuses to run while
  the backend is up, but now for the real reason: **both processes write the same SQLite file.**
- **Company-name matching is exact-then-word-boundary-prefix** (`LcaWageRepository.lookup`).
  LCA employer names are legal entities, so LinkedIn's "Amazon" (key `amazon`) has to reach
  `amazon com services`. Four tiers, tried in order: exact+state, exact+national, prefix+state,
  prefix+national; within a tier, highest `sample_count` wins, ties broken by `employer_key`.

  **The prefix predicate is word-boundary only** — `employer_key = :key OR employer_key LIKE
  :key || ' %'`. This is the `Meta`/`Metabase` trap from §3 again, and it is not theoretical:
  against real FY2024Q4 data a naive `LIKE 'meta%'` also matches `metapicks`, `metanoia
  solutions`, `metaforge it solutions` and `metagenomi`. Keys shorter than 3 chars never
  prefix-match. `LcaWageRepositoryTest` has the guard tests; **do not loosen this predicate.**

  Verified against the real file: `amazon` → Amazon.com Services LLC (1391 samples, beating AWS
  at 485), `meta` → Meta Platforms Inc (860, beating "Meta Soft" at 5), `jpmorgan` → JPMorgan
  Chase & Co. Exact still wins where it exists: `google` → Google LLC, not Google Public Sector;
  `apple` → Apple Inc., not Apple Payments Services.

  **Residual risk, mitigated in the UI, not the code.** "Meta Soft" and "Meta IT Systems" are
  real, distinct companies that legitimately match the key `meta`; sample_count ranking saves the
  common case but cannot be right in principle. So the matched entity is carried all the way to
  the browser: `lca_wage.employer_display` (the raw `EMPLOYER_NAME`) → `SalaryResult.matchedEntity`
  → `salary_estimate.source_detail` → `job_listing.salary_source_detail` → `JobResponse`. `JobRow`
  shows it, prefixed with `≈` and accent-coloured when it differs from the posting's company, so a
  wrong entity is *visible* rather than a silently wrong number.

  `looselySameCompany` in `frontend/src/utils/format.ts` decides exact-vs-fuzzy and is
  **equality-after-normalization only, deliberately not a prefix test** — a prefix comparison
  there would classify every backend prefix match as exact and the `≈` marker would never appear
  at all (and it would call "Meta" and "Metabase" the same company).
- **Keys live in `.env`** (gitignored), sourced by `run.sh`. `application.yml` reads
  `${ADZUNA_APP_ID:}` etc. `SALARY_SETUP.md` is the user-facing guide.
- The two-bucket sort (`JobSortOrder`, `utils/sort.ts`) was already built and correct; only the
  frontend **Min salary** input (`filterByMinSalary` in `utils/sort.ts`) is new. It hides rows
  with a *known* salary below the threshold and never hides unknown-salary rows.

---

## 9. ATS sources + AI resume matching (added 2026-09-07)

**What it does.** A run now pulls from one or more job sources instead of only LinkedIn. The
Greenhouse, Lever and Workday adapters fetch real job **descriptions**, and once the run has
collected everything, the Claude CLI compares each description against an uploaded resume and
sorts the results into **Recommended match** / **Not recommended**, each with a one-sentence
reason.

**LinkedIn cannot be combined with the ATS sources, and this is not a UI preference.** LinkedIn's
guest search returns no description (§2), so there is nothing for the scan to read. The rule is
enforced in three places: the source dropdown won't let you assemble the combination, the API
rejects it with a 400, and `findScannableByRun` only returns rows with a non-blank description.

### Verified API facts (measured live 2026-09-07 — trust these over any doc)

| | Greenhouse | Lever | Workday |
|---|---|---|---|
| Endpoint | `boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true` | `api.lever.co/v0/postings/{slug}?mode=json` | `POST {host}/wday/cxs/{tenant}/{site}/jobs` |
| Requests per company | **1** (whole board) | **1** (whole board) | **1 + N** |
| Native id | integer | **UUID string** | `JR2015623` |
| Description | `content`, **HTML-entity-escaped** | `descriptionPlain` (intro ONLY) + `lists[]` (sections, HTML) + `additionalPlain` - all three, see §13 | detail response only |
| Real posted date | `first_published` | `createdAt`, **epoch millis** | detail's `startDate` only |
| Keyword filtering | local | local | **server-side** (`searchText`) |
| Dead slug | HTTP 404 `{"status":404,...}` | HTTP 404 `{"ok":false,...}` | site unresolvable |

Measured sizes: Airbnb 167 jobs / 2 MB; Palantir 310 / 6 MB; Veeva 900 / 12 MB. Hence
`ats.max-response-bytes`. Fixtures for all of the above are real captures in
`src/test/resources/fixtures/greenhouse_*.json`, `lever_*.json`, `workday_*.json`.

**The non-obvious ones, each of which has a regression test:**

- **A Lever board with no open jobs returns HTTP 200 and `[]` — that is NOT a dead slug.**
  Verified: `kraken`, `wealthsimple` and `saronic` are all live boards currently returning zero
  postings. Treating an empty array as death would retire healthy companies permanently. Only a
  404 marks a slug dead.
- **Workday's `postedOn` is prose**, literally `"Posted 30+ Days Ago"`. It is not a date and must
  never be parsed as one. The real date exists only on the detail response. **Bug fixed
  2026-09-09:** Workday never applied the run's recency window at all (Greenhouse and Lever did,
  via `LocalFilter`), so a 24-hour run showed months-old Workday jobs. It now (a) uses the prose
  only as a *floor on age* to skip "30+ Days Ago" postings before spending a detail request,
  (b) enforces the window on the detail's `startDate` at day granularity
  (`LocalFilter.postedDayWithinRecency` - a job posted "yesterday" has a midnight timestamp
  older than 24h and must still pass), and (c) with a window set, **drops** jobs past
  `ats.workday.max-details-per-run` instead of returning them undated, since an undated row
  cannot be shown to be inside the window. Without a window they are still returned undated.
- **`job_listing.job_id` had to stop being LinkedIn's id.** It was `integer primary key` holding
  the numeric LinkedIn id; Lever's UUIDs and Workday's `JR…` strings don't fit. V5 rebuilds the
  table so `job_id` is an autoincrement **surrogate** and `(source, source_job_id)` is the
  natural key. Existing LinkedIn rows keep their original `job_id` verbatim, so the REST API,
  the frontend and any bookmarked `/api/jobs/{id}` link are unaffected.

### Workday site-id discovery via robots.txt — the thing that makes Workday work at all

Workday's API path needs `{host}/{tenant}/{site}`, but public slug catalogs list only the
hostname (`nvidia.wd5.myworkdayjobs.com`) and there is no site-listing endpoint. Guessing
conventional site names (`External`, `Careers`, `{Tenant}ExternalCareerSite`, …) hit **1 in 4**.

**`robots.txt` publishes it.** `GET https://{host}/robots.txt` returns `Allow: /{site}/`, and that
segment is the site id. Verified 5/5, each then returning HTTP 200 from the jobs API:

| tenant | site |
|---|---|
| `3m` | `Search` |
| `8x8inc` | `8x8_External_Careers` |
| `7eleven` | `7eleven` |
| `nvidia` | `NVIDIAExternalCareerSite` |
| `aah` | `External` |
| `salesforce` | `External_Career_Site` |
| `adobe` | `external_experienced` |
| `paypal` | `jobs` |
| `visa` | `Visa` |

Two real edge cases the resolver must survive, both encountered:
- robots.txt may carry **several** `Allow:` lines — collect them all and validate each against
  the jobs endpoint, first HTTP 200 wins. (`ebay.wd5` resolves to `TCGPlayer_External_Career`;
  that tenant genuinely only hosts the subsidiary board.)
- robots.txt may not be a robots file at all — `netflix.wd1.myworkdayjobs.com/robots.txt` returns
  `{"errorCode":"HTTP_422",...}`. Handle as unresolvable; never crash.

The resolved site is cached onto the `ats_company` row, so this costs one request per tenant ever.

### Catalog vs. selection — why imported companies are disabled

`./import-slugs.sh` ingests a catalog like
`https://raw.githubusercontent.com/elliottdehn/open-jobs/main/slugs.json`, whose shape is
`{"ats": {"greenhouse": [...], "lever": [...], "workday": [...]}}` across 25 ATS platforms; only
the three we implement are read. That file holds **15,576 slugs** for those three alone. A run
that visited all of them would be 15,000+ requests, so the catalog and the *selection* are
separate concepts: **imported rows land `enabled=0`, `status='unverified'`**, and a run only ever
visits `enabled=1 AND status<>'dead'`. Importing never re-enables or overwrites a company you
have already configured — there is a regression test for exactly that.

`V6__ats_sources.sql` seeds **42 companies I verified live** (26 Greenhouse, 10 Lever, 6 Workday),
enabled, so the feature works on first boot.

**Public slug catalogs decay fast, and this is why the dead-slug retirement exists.** Measured
against that file on 2026-09-07: **8 of 34** sampled Greenhouse slugs and **7 of 8** sampled Lever
slugs were already dead (`doordash`, `openai`, `ramp`, `notion`, `benchling`, `anduril`,
`sourcegraph`, `hashicorp` have all left Greenhouse; nearly every sampled Lever slug had moved).
**Measured properly on 2026-09-15 with `./validate-slugs.py`** (every unverified row probed live,
~20 min, stdlib Python): Greenhouse 2,700 of 8,123 dead (33%), Lever 1,404 of 3,587 (39%),
Workday 456 of 3,646 unresolvable (12.5%). The 4,566 dead rows were deleted (list in
`data/slug-validation/removed.csv`, DB backup in `data/backups/`), the 10,787 live ones are
`status='active'` but still `enabled=0`, carrying `last_job_count`, the real Greenhouse board
name, and for Workday the resolved `site` - so the Sources tab can now be sorted/searched by
what is actually there. Two things the validator taught: Workday's `robots.txt` answers HTTP 422
for dead tenants AND some live ones (Netflix), so the resolver's site guesses are the only way
in for those; and a Workday board can 502 on every site candidate (Whole Foods, 2026-09-15) -
treat that as transient, not dead.

A company that 404s `ats.dead-slug-threshold` runs in a row is set `status='dead'`, `enabled=0`
and never called again. It is **marked, not deleted** — a dead row is already skipped by every
run, and keeping it explains why a company disappeared from your results. `--prune` deletes them
if you want them gone.

### Driving the Claude CLI from Java

The scan shells out to the local `claude` binary — the user's subscription, no API key, no API
credits. `ai.cli-path` (env `CLAUDE_CLI_PATH`) locates it; `./run.sh` warns if it is missing but
never fails the boot, because everything else works without it.

```
claude -p --model sonnet --output-format json --json-schema '<schema>' \
       --no-session-persistence --safe-mode --restricted     < prompt
```

- **The prompt goes on stdin, never as an argv argument.** Passed as an argument the CLI still
  waits for stdin and emits `Warning: no stdin data received in 3s` — a 3-second stall on *every*
  call. Measured: 6.7s as an argument vs 3.9s piped. argv also has a ~1MB ceiling a batched
  prompt would breach.
- **Read `structured_output`, not `result`.** With `--json-schema` the response carries the answer
  already parsed into an object. Never regex the text.
- **~25k tokens of fixed overhead per invocation**, independent of payload — it is tool
  definitions, and `--system-prompt` does *not* reduce it (measured). **Batching is therefore the
  only real cost lever**, which is what `ai.batch-size` (9) exists for; `ai.concurrency` (3)
  bounds simultaneous CLI processes. `--bare` would cut the overhead but forces
  `ANTHROPIC_API_KEY`, defeating the point of using the subscription.
- `--safe-mode` + `--restricted` stop a scan from picking up this repo's own CLAUDE.md, skills,
  hooks or MCP servers, and remove the command-running tools.
- **Drain stdout and stderr on separate threads.** A process whose stderr pipe fills while you
  only read stdout deadlocks. `ClaudeCliClientTest` has a stderr-flood regression guard.
- `ClaudeCliClient` never throws — every failure is a `ClaudeCliResult` variant
  (`Ok`/`CliNotFound`/`Timeout`/`Failed`) — and `ResumeMatchService` never throws either, exactly
  like `SalaryEnrichmentService` (§8). **An AI failure must never turn a successful collection run
  into a failed one.**
- Verdicts are cached in `ai_match` keyed `(job_id, resume_id)`, so a re-scan costs zero CLI
  invocations for anything already scored. Same "only look where no record exists" rule as the
  salary cache.
- Verdicts are correlated back by an echoed `ref`, **never positionally** — the model may return
  them out of order, and there is a test that deliberately reorders them.

### Tests must never touch the real database

`JobdashApplicationTests` used to boot with no datasource override, which meant `@SpringBootTest`
inherited `application.yml` and ran Flyway against the developer's own `data/jobdash.db`. That
both mutates real data and makes the test fail for reasons unrelated to the code — a half-applied
migration history in that file fails Flyway validation even though a fresh clone migrates
perfectly. It now points at a `@TempDir`, like every other full-context test here. **Keep it that
way.**

### Two failures found only in real use (2026-09-08)

Both shipped past a fully green 307-test suite, and both are §1's signature failure again.

**1. `(Long) rs.getObject("resume_id")` threw `ClassCastException` at runtime.**
SQLite's JDBC driver returns the *narrowest* type a value fits in, so a small `resume_id` comes
back as an `Integer` and the cast to `Long` blows up. The trap is that **a NULL column casts
fine** — so it only failed once a run actually had a resume attached. Every end-to-end check I
had run used `curl` without `resumeId`; the UI always sends one. It broke `GET /api/runs`, which
is the first call the dashboard makes, so the whole app looked dead.
Read nullable numerics as `getLong(...)` followed *immediately* by `wasNull()`, never
`(Long) getObject(...)`. `SweepRunRepositoryTest` covers both the null and non-null cases, and
was confirmed to fail against the old code before the fix landed.
The same file had a second instance of the pattern: `AiMatchRepository.mapRow` called
`wasNull()` five columns after the `getLong` it referred to. **`wasNull()` reports on the most
recent read**, so it was really asking "was `model` null?" and would have turned a null `run_id`
into `0`.

**2. An interrupted run wedged the application permanently.**
A run lives on a virtual thread inside one process. Kill that process — a crash, a Ctrl-C, an
ordinary restart — and the thread dies while its `sweep_run` row keeps `status='running'` and a
null `finished_at` forever. After that, `POST /api/runs` refuses to start anything ("a run is
already in progress") **and** `POST /api/runs/{id}/cancel` refuses too, because the run isn't in
the new process's registry. There was no way out but editing the database by hand.
`sweep/OrphanedRunReaper` now closes out every unfinished run at startup with the terminal status
`interrupted`. This is safe precisely because of the single-process rule: nothing can still be
running when the app has only just booted. **If you add another way for a run to outlive its
process, this reaper is what keeps the app usable.**

### Watching an AI scan (added 2026-09-08)

The scan was the one phase with no feedback: status flipped to `scanning` and the UI sat there
for however long it took. Two surfaces now report it, both fed from the same counters.

**In the dashboard.** `ai/ScanProgress` snapshots ride on the existing SSE stream — `SweepProgress`
carries a nullable `scan` field, and `RunController` already treated `scanning` as non-terminal so
the stream stays open. `RunProgress.tsx` renders batches done/total, jobs scored/total, the
running recommended/not split, failed batches, cost, and an elapsed clock.

**Two details that would otherwise be silently wrong:**
- **Progress is counted where a batch actually finishes, not in the drain loop.** `runBatches`
  reads its futures in *submission* order, so with `ai.concurrency` batches in flight a fast
  batch 3 would stay invisible behind a slow batch 1 and progress would move in lurches.
- **Cost is accumulated as integer micros in an `AtomicLong`**, not by adding doubles from several
  threads. There is no cheap atomic double-add, and repeated concurrent addition drifts.

A listener is UI plumbing, so `publishQuietly` swallows anything it throws —
`aListenerThatThrowsDoesNotBreakTheScan` guards that a rendering bug can never cost real scan
results.

**In a terminal.** `logback-spring.xml` routes the `jobdash.ai.scan` logger to `logs/ai-scan.log`
with `additivity="false"`, so scan lines do **not** also land in the console or `backend.log` —
that separation is the point of the file. Follow it with `tail -f logs/ai-scan.log`:

```
17:10:32 INFO  scan START run=13 resume="Backend - senior" scannable=4 cached=0 to-scan=4 batches=1 ...
17:10:32 INFO  batch 1/1 START (4 jobs)
17:10:52 INFO  batch 1/1 done  20504ms  cost=$0.0743  recommended=2 not-recommended=2
17:10:52 INFO  scan DONE  run=13 scanned=4 recommended=2 not-recommended=2 skipped=0 ... elapsed=20s
```

**Never log the prompt or the resume through `scanLog`** — it carries the user's CV. Titles,
counts, timings and verdicts only. `logback-spring.xml` also re-includes Spring Boot's
`defaults.xml` and `console-appender.xml`; dropping those includes silently turns off normal
logging everywhere else.

**Granularity is bounded by `ai.batch-size`** (default 9): a 4-job scan is a single batch and so
only ever reports 0/1 then 1/1. Lower the batch size for finer-grained progress, at the cost of
more CLI invocations — each one carries the ~25k-token fixed overhead described above.

**Deliberately not built: streaming Claude's own output.** It works (`--output-format stream-json
--include-partial-messages`), but because we pass `--json-schema` the deltas are raw JSON typing
itself out — `'{"results": [{"ref":"a","recommended":true,"reason":"An 8-year...'` — and with
`ai.concurrency: 3` you get three interleaved JSON streams. Measured, then rejected as noise.

### Three fixes from real use (2026-09-08, later)

**1. The page went dead during the AI scan.** The server was streaming scan progress correctly;
the *browser* was hanging up. `useRunStream` closed the `EventSource` on
`data.status !== "running"`, and `scanning` is not `"running"` — so the client disconnected at the
first scan event and nothing arrived until a manual reload. `App.tsx` had the same shape and so
fired its "run finished" refresh when the scan *started*, loading the results list before a single
verdict existed and then never refreshing again.

Both now go through `isRunInFlight()` in `utils/runStatus.ts`, which owns the one definition of
"still working" (`running` + `scanning`). **Never compare a status to `"running"` directly** —
that is what broke, in three separate places, and a fourth (`RunProgress`'s cancel button) was
about to.

**A fifth instance, one level deeper (2026-09-10).** `isRunInFlight()` closed the hole above, but
it still trusts `status` alone, and `status` itself can lie for a moment. `SweepService.collect()`
publishes the *LinkedIn collection phase's own* outcome (e.g. `"ok"`) into the shared in-memory
progress registry the instant collection ends - before `DetailFetchService` (or, for a run with no
detail phase, the scan) does its own setup and publishes an in-flight status a moment later. The
SSE stream is polled every 750ms independently of these transitions, so a poll can land squarely
inside that gap and hand the client a status that looks terminal while the run is still very much
in progress. `useRunStream` closed the `EventSource` on it (AI scan progress never showed again
without a manual reload) and `App.tsx` fired its "run finished" refresh and switched to the Results
tab before the scan had even started - the same two symptoms as fix #1, caused this time by the
backend's own status being momentarily wrong rather than the frontend misreading a correct one.

The fix is `isRunFinished()` in `utils/runStatus.ts`: a run is only "done" once `finishedAt` is
also set, and `RunOrchestrator.finishRun()` persists that timestamp to the row *before*
republishing the matching terminal status into the registry - so pairing the two closes the gap.
`useRunStream` and `App.tsx` both go through it now. **A status outside `IN_FLIGHT_STATUSES` is
still not proof a run is over - pair it with `finishedAt`.**

**The same gap was reachable server-side, too - `RunController.pushProgress` had the identical
bare-status check** (`if (!IN_FLIGHT_STATUSES.contains(status))`) deciding whether to
`emitter.complete()`. A poll landing in the same collect()-to-detail-fetch window made the
*server* end the SSE response, which fires the browser's `onerror` regardless of the frontend fix
above - the client never even got a status to check. `pushProgress` now requires
`run.finishedAt() != null` too, the same pairing as `isRunFinished()`, race-free for the same
reason. Covered by `RunControllerStreamTest` (real MockMvc async streaming request against a
`@TempDir` SQLite DB): registry status `"ok"` + `finished_at` null keeps the stream open; the same
status with `finished_at` set completes it.

**`useRunStream`'s `onerror` handler compounded this.** It called `source.close()` on any error,
which sets `readyState` to `CLOSED` and cancels the browser's own automatic reconnect - so *any*
transient drop (this server-side one, a real network blip, or `SSE_TIMEOUT_MS` lapsing on a very
long scan) killed live progress for good until a manual reload. It now only surfaces a "retrying"
note and leaves the `EventSource` alone; the browser reconnects on its own to the same (idempotent
GET) endpoint, and the progress handler clears the note on the next event. The genuine-finish path
is untouched: the client still closes deliberately, from inside the `progress` handler, the moment
`isRunFinished()` is true - including on a reconnected stream, since its first event carries
whatever the run's current state actually is.

**2. Foreign postings were flooding the results.** Two independent causes:
- **Workday applied no location filtering at all.** It filters keywords server-side and nothing
  else, and `LocalFilter` was only ever wired into Greenhouse and Lever. The fix is applied
  centrally in `AtsSweepService`, not inside the individual sources, so it covers every source
  including any added later.
- **`matchesLocation` was a naive substring**, so a request for `"United States"` failed to match
  `"New York, NY"` — it would have discarded nearly every genuine US posting while claiming to
  filter for them.

`source/UsLocation` decided this with hand-written country/state/city lists. **That class is gone
as of the section below — location is now judged by Claude.** The history is kept because it is
the argument for why: the matcher needed four corrections just to handle strings already in the
database (`"California - Remote"`, `"Virginia - Mclean"`, `"US, Remote"`, bare `"San Francisco"`
were all wrongly rejected), and it still called `"Remote - CA"` American with full confidence when
the string is just as likely Canada. On the user's real data it removed **147 of 228** collected
ATS postings (64%) — the right order of magnitude, by a method that could not be trusted at the
edges.

`usOnly` defaults to **true** and is opt-out (`usOnlyOrDefault()`), with a checkbox on the run
form. Note that a company returning only foreign roles is still recorded `active` — liveness is
whether the board answered, not how many postings survived our filters.

**3. The AI reason ran out of its column.** Every `.job-table td` is `white-space: nowrap` so the
other columns stay on one line, and the reason `<span>` inherited it, rendering one unbroken line.
`.ai-reason` now sets `white-space: normal` plus `overflow-wrap: anywhere`, with the title link
keeping its own single-line ellipsis. Reasons run to ~300 characters, so the two-line clamp still
bites — the full text is in the `title` attribute on hover.

### Where the scan's "cost" number comes from (2026-09-08)

**We do not calculate it.** `ClaudeCliClient` reads `total_cost_usd` verbatim out of the CLI's own
JSON result for each invocation (`ClaudeCliClient.parse`), and `ResumeMatchService` sums those
per-batch values — twice, on purpose: `ScanResult.totalCostUsd` for the final summary, and
`ScanCounters.costMicros` for live progress. The live one accumulates **integer micros in an
`AtomicLong`**, because there is no cheap atomic double-add and repeated concurrent addition of
doubles drifts.

Measured on this machine, and the reason `ai.batch-size` matters more than anything else:

| Batch | Jobs | Cost |
|---|---|---|
| trivial 2-job probe | 2 | $0.1007 |
| real scan | 9 | $0.1315 |
| real scan | 9 | $0.1268 |
| real scan | 9 | $0.1466 |

A 2-job call and a 9-job call cost almost the same, because the per-invocation overhead dominates:
that probe reported **24,391 `cache_creation_input_tokens`** against 2 input and 205 output tokens.
The job descriptions are a rounding error next to the fixed cost of standing the session up.

So 27 jobs in 3 batches came to **$0.40**. One job per call would have been 27 × ~$0.10 ≈ **$2.70** —
roughly 7× more for the same work. Raising `ai.batch-size` lowers cost and reduces progress
granularity; lowering it does the reverse.

Note this figure is what the CLI reports for the tokens used, not necessarily an incremental charge:
these runs go through the user's Claude subscription rather than a metered API key.

### A second US check at scan time (`ai.us-only`, default true)

`AtsSweepService` filters non-US postings at collection, but that only covers rows arriving from a
run with `usOnly` on. Anything collected before the filter existed, or by a run with it off, would
still have been sent to Claude and surfaced as a match. `ResumeMatchService` now re-checks
`UsLocation.isUnitedStates` immediately before batching, logs what it drops, and
`nonUsJobsAreNeverSentToTheCliWhenUsOnlyIsOn` asserts on the **CLI invocation count** — proving the
posting never left the machine, not merely that no verdict was stored. Verified live by planting an
`"Israel, Yokneam"` row and watching the scan skip it.


### Claude decides the location, not a pattern matcher (2026-09-08)

`UsLocation` is deleted. `source/location/LocationClassifier` asks the `claude` CLI whether each
free-form location string refers to the United States.

**Measured before building it**, over the 73 distinct real location strings then in the database:
one batched call, **73/73 correct, $0.15, 27 seconds**. The same call also returns a `confident`
flag, which isolated exactly the 11 genuinely ambiguous strings — the eight Workday
`"N Locations"` placeholders, `"Hamburg"`, `"San Jose"`, and `"Remote - CA"`. The matcher had no
way to express "I cannot tell", which is why it failed silently rather than visibly.

**Three states, and the difference between them is the whole design.**

| `location_us` | `location_confident` | Meaning | Visible? |
|---|---|---|---|
| NULL | NULL | not classified yet | **yes**, retried next run |
| 1 | 1 or 0 | in the US | yes |
| 0 | 0 | probably not US, but Claude could not tell | **yes**, flagged `⚠ uncertain` in the UI |
| 0 | 1 | confidently not US | no |

So the reading rule everywhere is *hide only a confidently non-US row*. A missing CLI must never
silently shrink the user's results.

**The SQL trap that cost a debugging cycle.** Written the obvious way —
`not (location_us = 0 and location_confident = 1)` — an unclassified row makes `location_us = 0`
NULL, so the whole `NOT(...)` is NULL, and `WHERE` drops it. That hid *every* row the classifier
had not reached: the exact opposite of the intent, with no error. The live form is
`not (coalesce(location_us, 1) = 0 and coalesce(location_confident, 0) = 1)` and
`JobListingRepositoryTest.onlyConfidentlyNonUsRowsAreHiddenFromEveryReadPath` is the guard.
**Do not remove the coalesce.** This is §1's signature failure again.

**Why it is not stored in `filter_verdict`,** despite that being the single column that already
hides rows from the search tab, the AI scan and salary enrichment:
`FilterEngine.reevaluateStale()` rewrites that column wholesale from title/company rules, so a
location decision parked there would be silently reverted the next time an exclude word changed.

**Why classification runs after collection, not during it.** `AtsSweepService` now stores whatever
a board returns and judges nothing. `RunOrchestrator` then runs one batched call for the whole run
before the AI scan. Classifying inside the per-company loop would have cost up to one call per
company (150 per run); this costs one, and often zero.

**The cache is the cost argument.** Every decision is kept in `location_verdict`, keyed on a
normalised string (`LocationKey`: lowercase, collapse whitespace — but **not** strip punctuation,
because `"USA.VA.Reston"` and `"US, Remote"` depend on their separators). **No TTL**: which country
a string names does not change. Measured live: a first run classified 5 strings for $0.1440; the
next run over the same boards reported `distinct=7 cached=5 to-classify=2` and cost **$0.0395**.
29 rows of "San Francisco Bay Area" spelling variants collapsed to one cache key.

`ClaudeCliClient` gained `runStructured(prompt, jsonSchema)` returning a payload-agnostic
`CliJsonResult`, so a second caller can define its own response shape. `run(prompt)` is now a thin
job-match-shaped delegate over it and kept its signature, so `FakeCliClient` and the stub-script
tests were untouched by the refactor.

**Build note learned the hard way here:** `./mvnw compile` and `./mvnw test` can report BUILD
SUCCESS against stale classes — a record with 26 components and a 24-argument constructor call
"compiled" cleanly, and a broken predicate produced 21 phantom failures that vanished on a clean
build. **Verify with `./mvnw clean test`.**

---

## 10. LinkedIn job descriptions: the detail-fetch phase (added 2026-09-09)

**What it is.** LinkedIn's search cards carry no description (§2), so until now LinkedIn rows were
never AI-scanned. A LinkedIn run now has a second phase after collection: `DetailFetchService`
reads the anonymous detail fragment (`/jobs-guest/jobs/api/jobPosting/{id}`, verified in §2 to
carry the full 4-7.5k-char description) for the run's newest passing rows, stores the plain text
and its hash on `job_listing`, and then the same `ResumeMatchService.scan` an ATS run gets runs
over those rows. `RunOrchestrator` now drives LinkedIn runs too (`SweepService.createRun` +
`collect`, then details, then scan, on one virtual thread); `SweepService.run()` remains as the
collect-and-finish shape the sweep tests drive.

**Decisions, and why:**

- **There is no separate "detail budget".** Every detail fetch goes through
  `PacedHttpClient.fetchDetail`, i.e. the same pacing gate, per-run cap, rolling-24h budget,
  breaker and `request_log` row as a search page - it is the same host from the same IP, and
  LinkedIn does not care which URL path tipped it over. **The user's decision, same day: fetch a
  description for every passing job, no artificial cap.** `sweep.detail.max-per-run` is 0 (only
  the shared budget bounds the phase) and the shared numbers went from 150/run and 300/day to
  **500/run and 1000/day** so a 24h sweep (~50 pages + ~330 details) fits with a second run to
  spare. That is far above the 200-300/day the original spec called safe; the pacing and the
  breaker are the protection, and if LinkedIn blocks, the run pauses. A first attempt capped the
  phase at 60/run and was rejected - don't reintroduce a cap without asking.
- **Newest first, filter-pass only, untriaged only, once ever, across all runs.** The queue is
  the `job_detail_queue` partial index plus `source='linkedin'` and `user_status is null`,
  `posted_at desc` - deliberately NOT scoped to the current run, so rows a blocked or budget-
  exhausted run left behind are drained by the next one instead of being stranded. Anything the
  user already triaged is not worth a request. An `ok` or `gone` row is never visited again -
  `detail_fetched_at` is the dequeue flag for both.
- **Three outcomes, per §5's rule, plus one refinement.** `OK` → store; `GONE` (HTTP 404) →
  `detail_status='gone'`; `BLOCKED` (429/999), a budget refusal or an open breaker → stop the
  phase, leave every remaining row untouched so the next run retries it. The refinement: a 200
  with no description block, a 5xx or a transport error **skips that one row and continues**.
  It still counts as a soft failure on the breaker, so if it is systemic the breaker trips after
  two and the next fetch is refused - but one odd page (a sign-in wall on a single posting) must
  not throw away the other 59.
- **The scan runs even when the detail phase stopped early.** A budget refusal at 40/60 still
  leaves 40 real descriptions; skipping the scan would waste them. Only a cancellation skips it.
  The run's *reported* status is then the detail phase's stop reason (`budget_exhausted`,
  `blocked`, `capped`), so the user learns why fewer rows got a verdict.
- **Plain text, not HTML, and the hash is computed in Java.** `DetailParser` renders the
  `show-more-less-html__markup` block to text with `<br>`/block/`<li>` structure kept as
  newlines and bullets (Jsoup's `text()` runs every bullet into one line, which reads badly in a
  prompt). `description_hash = md5(lower(whitespace-collapsed text))` via `DetailParser.hashOf`,
  the §5 formula, so relay detection can finally be built on it. Seniority / employment type /
  job function / industries are parsed into `JobDetail` but **not stored** - no column, no
  consumer yet.
- **A bug found on the way: the per-run cap was never reset.** `RateLimiter.resetRunCount()`
  existed but nothing called it, so "per run" was really "per process": the second sweep after a
  boot would end `capped` about 50 pages in. `PacedHttpClient.beginRun()` now calls it at the
  start of `SweepService.collect`. There is a test for it.
- **`fetching_details` is a new in-flight status.** Like `scanning`, it must be treated as
  in-flight by both `RunController` (the SSE stream stays open - `IN_FLIGHT_STATUSES`) and the
  frontend (`IN_FLIGHT_STATUSES` in `utils/runStatus.ts`). Miss one and the browser hangs up the
  moment the phase starts, exactly the bug the `scanning` comment in that file describes.
- **Mixed runs are allowed** (the user asked for them the same day). One `sweep_run`, one
  thread: LinkedIn collect → ATS collect → LinkedIn details → location classification → scan.
  The two collections are independent and each keeps its own stop reason; `SweepService` and
  `AtsSweepService` now build every progress snapshot on the current one instead of from zero,
  and the ATS counters start from wherever LinkedIn left them, so a mixed run's totals are
  cumulative. An ATS `no_sources` is downgraded to `ok` when LinkedIn was part of the run.
  `SweepService.startRun`/`run` survive only for the tests that drive the loop synchronously.
- **The run form used to withhold the resume on a LinkedIn-only run** (`RunControls` only sent
  `resumeId` for ATS runs, from when LinkedIn had no scan). It always sends it now; the default
  resume would have been used regardless, but an explicit pick was silently ignored.

**Fixtures.** The parser tests use the three live fragments already in
`src/test/resources/fixtures/frag_*.html` (captured 2026-08-28). No new live traffic was spent
building this. A hand check in test mode costs 3 search pages plus at most 60 details - set
`sweep.detail.max-per-run` low first if you want it cheaper.

### Retry after a cooldown: resuming a run (added 2026-09-16)

Real-use failure: run 29 got a 429 on search page 20, the breaker opened for 30 minutes and the
run ended `blocked`. Because the detail phase only follows an "ok" LinkedIn collection, its 80
passing LinkedIn rows never got a description, showed up in the Results tab as "Not scanned",
and **Re-scan did nothing** - the scan only reads rows that have a description, so it logged
"nothing to do" four times while the user kept clicking. And there was no way back: the panel
said "wait and try again" with nothing to click.

Two pieces fix that, and they are deliberately separate from starting a run:

- `POST /api/runs/{id}/resume` → `RunOrchestrator.resumeRun`. Re-opens the finished row
  (`SweepRunRepository.reopen`: status back to `fetching_details`, `finished_at` cleared) and
  runs **only the phases the cooldown cut short**: the detail fetch, then the scan. It never
  re-runs the search - the pages the run did not reach are a new run's job - and never touches
  the boards or location classification. Same guards as `POST /api/runs`: one run at a time,
  and refused with 503 while the breaker is open, because it spends the same LinkedIn budget.
  The terminal status is the detail phase's outcome, so a resume that gets blocked again ends
  `blocked` again and stays retryable.
- `GET /api/runs/cooldown` reports the breaker (`active`, `until`, `remainingSeconds`).
  `RunProgress.tsx`'s `RetryPanel` polls it every 15s while a retryable run is shown, counts down
  locally, and enables "Retry: fetch N descriptions and re-scan" the moment it lapses. `N` is
  `RunResponse.unfetchedDescriptions`, attached by the controller **only to finished runs** (the
  count is moving while the detail phase works, and the SSE poll would pay for it every tick).

Because the run id does not change, the browser's `EventSource` - which closed itself when the
run first finished - has to be re-opened: `useRunStream(runId, attempt)` keys its effect on both,
and `App.handleRunResumed` bumps `attempt`. Re-scan itself now explains the "Scanned 0" case
instead of pretending it worked: it counts the LinkedIn rows on screen with no description and
points at Retry.

Tests: `RunOrchestratorTest` (resume order, blocked-again, ATS-only, in-flight refusal) and
`JobDashApiTest` (cooldown endpoint, resume 404/409/503, unfetched count only on finished runs).

---

## 11. Posting-stated salary, extracted during the AI scan (added 2026-09-10)

**What it does.** The AI resume scan already reads each job's full description; it now also
extracts a salary from it when the posting explicitly states one, and writes it straight to
`job_listing` as ground truth - a number the poster gave us beats one any of the three external
sources (§8) merely estimated. `SalaryEnrichmentService` skips its cascade for a row whose own
description already looks like it states a salary, so the two paths don't race or duplicate work.

**Schema/prompt (`ai/MatchPromptBuilder`).** `RESULT_JSON_SCHEMA` gained two optional integer
fields, `salaryMin`/`salaryMax`, alongside the untouched required `ref`/`recommended`/`reason`.
The prompt instructs the model to: extract ONLY an explicitly stated figure (not infer one from
title/seniority/location); annualize an hourly rate by ×2080; set both fields to the same value
for a single stated figure; and omit/null them rather than default to 0 when nothing is stated.
`MatchVerdict` carries the two as nullable `Integer`s, with a 3-arg convenience constructor kept
so every existing call site (`new MatchVerdict(ref, recommended, reason)`) still compiles
unchanged. `ClaudeCliClient.toVerdicts` reads them as optional (absent/null → `null`, never `0`).

**Applying results (`ai/ResumeMatchService`).** A new `JobListingRepository.applyPostingSalary`
sets `salary_min`/`salary_max` and stamps `salary_source = 'posting'`, and clears
`salary_source_detail` (that column's "matched entity ≈ label" is an estimate-only concept and
would otherwise linger stale after being overwritten by a posting figure). It unconditionally
overwrites - the same unconditional `update` shape as `applySalary` - so a posting figure always
wins once the scan reaches a row, regardless of order: enrichment (§8) already only touches rows
with no salary yet, so there's nothing to change there for "posted beats estimated" to hold.
Applied in `runOneBatch`, per verdict, right after the `AiMatch` is built, gated through
`applyPostingSalaryIfValid`: **the model can hallucinate**, so a verdict is rejected (row left
untouched, logged at debug) when `salaryMin > salaryMax` or either figure falls outside a
10,000-2,000,000 annual USD sanity band - deliberately wide (a token annual salary at the low end,
a comfortably-covers-any-real-posting ceiling at the high end) so it only catches actual garbage,
not a legitimately low or high real figure. Posting salaries are never written to
`salary_estimate` - that cache is keyed `(company, title)` and shared across every job with that
pair, while a posting figure is specific to the one job whose own text stated it.

**Skipping external sources (`salary/SalaryEnrichmentService`).** Before running the cascade for a
row, `hasExplicitSalary(job.description())` gates it: a conservative regex
(`\$\s?(\d[\d,]*)(?:\.\d{1,2})?`) finds every dollar figure in the description, and if any parses
to **≥ $20,000** the row is skipped entirely - no source lookups, no `salary_estimate` cache write
(logged at debug) - left for the scan to fill in authoritatively later. Below that floor, the
cascade runs exactly as before. The $20,000 floor is deliberately a single condition rather than
two: the design brief called for "≥5-digit figure OR a `$ddd,ddd`-`$ddd,ddd` range", but any range
shaped like `$150,000 - $180,000` already trips the single-figure check on either side (150000 is
one dollar figure ≥ $20,000), so a second range-shaped pattern would have been redundant - one
regression test (`smallBonusFigureStillGoesThroughTheCascade`) guards that "$5,000 signing bonus"
does *not* trip it, and another (`explicitSalaryRangeInDescriptionSkipsTheCascadeEntirely`) guards
that a real range does, asserting on the stub source's own invocation count (HANDOFF §1's rule:
assert the positive outcome, not merely "it didn't throw"). If a row this gate skips never gets
AI-scanned (feature disabled, no resume, run cancelled before the scan phase), it simply keeps a
null salary forever - identical to an ordinary cascade miss, and considered acceptable rather than
worth a fallback path.

**The caching caveat, left unfixed on purpose.** `ai_match` rows are cached forever per
`(job_id, resume_id)`, and a cached job is never re-sent to the CLI (§9) - so a job already scanned
*before* this feature shipped will never retroactively pick up a posting salary; it would need a
fresh scan against a new/changed resume, or a manual `rescan`, to be revisited. No backfill was
built for this, per the brief - a targeted `rescan(jobIds, ...)` call is the escape hatch if a user
ever needs one, since that path already exists and bypasses nothing new.

**Chose not to add salary columns to `ai_match`.** The schema question (should the extracted
salary also live on the cached verdict, for audit/debugging - "what did the model see when it
decided to skip the cascade for this job?") was considered and skipped: it would touch
`AiMatchRepository.upsertAll`/`mapRow`, the `AiMatch` record, and a new migration (V12, since V11
already exists), for a value that's already durably visible in the one place a user or the UI
would ever look for it (`job_listing.salary_min/max/source`). `job_listing` is this feature's only
schema-level change, and there is no V12 - no migration was needed.

**Tests added**, all in the existing per-package files, using the existing test doubles
(`FakeCliClient` for the scan, `FakeSource` for enrichment - the latter grew an invocation counter
to assert the zero-lookups claim directly): `MatchPromptBuilderTest` (schema carries the new
fields, prompt carries the extraction rules), `ResumeMatchServiceTest` (a verdict with a salary
lands on the row with `source='posting'`; it overwrites an existing `'adzuna'` estimate; an
inverted or out-of-range verdict is rejected and the row stays untouched; a verdict with no salary
leaves the columns alone), `SalaryEnrichmentServiceTest` (an explicit `$150,000 - $180,000`
description makes zero source-lookup calls and writes no cache row; a `$5,000 signing bonus`
description still runs the cascade normally; a blank description is unaffected either way).

---

## 12. Apply with Claude: browser-driven applications (added 2026-09-17)

**What it does.** The AI scan (§9) judges a job; this feature acts on that judgment. A new **Apply
with Claude** button sits in the Results tab's bucket bar, next to Re-scan, and operates on the
Untriaged tab's **Recommended match** bucket. Click it and, for each non-LinkedIn job in that
bucket, Claude opens the real posting in a real Chrome tab, finds the apply form, fills it from the
uploaded resume plus a small **applicant profile**, attaches the resume file, and either stops on
the review step or clicks through to Submit — controlled by a **Submit applications** checkbox
next to the button, **default off**.

Backend: the `apply/` package (`ApplyProperties`, `ApplyPromptBuilder`, `ApplyOrchestrator`),
`web/ApplicationController` (`GET/PUT /api/profile`, `POST /api/applications`,
`GET /api/applications/current`, `GET /api/applications/{id}`,
`POST /api/applications/{id}/cancel`), and three new tables — `applicant_profile` (single row),
`apply_batch` (one row per click), `job_application` (one row per job per attempt) — added in
`V12__applications.sql`, behind `ApplicantProfileRepository`, `ApplyBatchRepository`,
`ApplicationRepository`. Frontend: `ApplyControls.tsx` (the button, the checkbox, a 2s poll, a
Cancel button, and re-attachment to an in-flight batch on reload via `/api/applications/current`)
and `ApplicantProfileForm.tsx` (rendered at the bottom of the Resumes tab). `JobResponse` gained
`applicationStatus` / `applicationNotes`, surfaced as a status badge on each `JobRow` (**Needs
review** / **Submitted** / **Apply failed** / **Apply manually**). Config lives in a new `apply:`
block in `application.yml` (`enabled`, `model: sonnet`, `timeout: 10m` per job, `max-turns: 60`,
`max-budget-usd: 2.0`, `max-description-chars: 4000`). Logs go to `logs/apply.log` via the
`jobdash.apply` logger, same shape as `jobdash.ai.scan` → `logs/ai-scan.log` (§9) — **the prompt,
the resume text and the applicant profile are never logged**, only titles, outcomes and timings.

### Driver decision: Claude in Chrome, not Playwright MCP

The user chose the **Claude in Chrome extension** (`claude -p --chrome`), driving their own real
Chrome window, over a Playwright MCP fallback that was designed but never built (see below).
Prerequisites, all one-time: Google Chrome (or another Chromium browser), the [Claude in Chrome
extension](https://chromewebstore.google.com/detail/claude/fcoeoabgfenejglbffodgkkbkcdhcgfn)
(≥1.0.36), Claude Code signed in via `/login` (an API key or setup-token **disables** Chrome
integration), and one interactive `claude --chrome` run from this repo's directory to accept the
one-time permission dialog. Claude opens tabs in the user's actual browser window, so the user
watches every action it takes — nothing runs headless or out of sight.

**The exact invocation, measured working non-interactively** (prompt on stdin, as always — §9):

```
claude -p --chrome --model sonnet --output-format stream-json --verbose --session-id <uuid> \
       --json-schema <schema> --strict-mcp-config --tools Read \
       --allowedTools mcp__claude-in-chrome Read \
       --max-turns 60 --max-budget-usd 2.0
```

Three non-obvious findings got here, each the cost of a probe:

1. **The allow rule must be the bare server name `mcp__claude-in-chrome`.**
   `mcp__claude-in-chrome__*` looks like the obvious wildcard and is **not** one — with it,
   navigation still worked, but `javascript_tool` and `computer` (the screenshot tool) both landed
   in `permission_denials` and the run burned every turn it had retrying blocked actions.
2. **The very first `--chrome` run writes the native-messaging host file** at
   `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.anthropic.claude_code_browser_extension.json`
   and then fails anyway with "Browser extension not connected" — Chrome only reads that directory
   at startup, so the fix is to restart Chrome once, after which it connects. (A similarly named
   `com.anthropic.claude_browser_extension.json` in the same folder belongs to the separate Claude
   desktop app, not Claude Code — don't check for that one.) `run.sh`'s preflight (below) checks
   for exactly this file's presence, not whether Chrome has picked it up yet.
3. **`--safe-mode` must NOT be on this invocation.** The resume scan's `ClaudeCliClient` call
   (§9) hard-codes `--safe-mode --restricted`, but `--safe-mode` disables MCP servers outright, and
   Chrome integration **is** the `claude-in-chrome` MCP server — using the scan's flags here would
   silently mean no browser control at all. This is why `ClaudeCliClient.runStructured` had to grow
   a `CliOptions` parameter instead of hard-coding one flag set for every caller.

Probe costs, for calibrating what to expect: reading a Greenhouse board's page title back = 6
turns, $0.07. Uploading a file from `data/` into a test form's file input = 14 turns, $0.19. A
real application — navigate, locate the form, fill several fields, upload, review — runs
**roughly $0.30–$1.50**, which is what the batch summary's running cost total will show.

### Design decisions, and why

- **One CLI call per job, sequential — not one session for the whole batch.** Keeps each call's
  context small (no accumulating browser state across unrelated postings), gives a real structured
  verdict per job instead of one verdict guessed at batch end, and lets timeout/budget/cancel apply
  **per application** rather than to the whole run. It also bounds the blast radius of the failure
  mode in the next point.
- **A dropped extension connection costs one job, not the batch.** The Chrome extension's service
  worker can idle out on a long-running session; because every job is its own `claude -p --chrome`
  process, a connection drop mid-job fails that one job (`failed`, with the CLI's own error in
  `notes`) and the orchestrator moves on to the next, instead of losing everything already done.
- **Polling, not SSE.** An apply batch advances once per job — often a minute or more apart — so a
  2s poll against `GET /api/applications/{id}` costs nothing and avoids standing up a second
  `SseEmitter` scheduler for a rate of change SSE has no advantage at. `RunController`'s SSE pattern
  (§9's scan progress, §10's run stream) stays the reference if per-browser-action streaming is
  ever wanted.
- **Submit toggle, default OFF.** The default outcome is `needs_review`: the form is filled, the
  resume is attached, and the tab is left open on the review step for the user to press Submit
  themselves. Wrong answers only ever reach a real employer when Submit is deliberately ticked —
  everything else is a form sitting there for a human to check.
- **LinkedIn rows are skipped ("Apply manually"), with zero CLI calls.** Applying through LinkedIn
  needs the user's own logged-in account, and §2's rule — never authenticate, never expose the
  real account to automation — is the one non-negotiable constraint in this whole codebase (also
  stated in the README's "How it gets the data"). Extending that automation to *applying* would be
  exactly the cookie-replay-adjacent risk §2 exists to avoid. Only Greenhouse / Lever / Workday
  postings get the Claude-driven flow.
- **A `submitted` outcome marks the job `applied`.** `ApplyOrchestrator` calls
  `JobListingRepository.setUserStatus(jobId, APPLIED, now)` exactly when the CLI's structured
  result says `submitted` — a `needs_review` result leaves `user_status` untouched, because nothing
  has actually been applied to yet.
- **The model is told never to invent work-authorization, sponsorship, salary or EEO answers.**
  These are exactly the questions a resume cannot answer and that carry real consequences if
  guessed wrong. Anything the model can't answer from the resume or the profile is left blank on
  the form and listed in `unanswered`, which lands in the job's `job_application.notes` for the
  user to fill in by hand.
- **The applicant profile exists because a resume answers none of the above.** A resume is "what
  have you done"; work authorization, sponsorship, location, salary expectation and links are a
  different category of question that the same person answers identically across every
  application, hence one profile row (`applicant_profile`, `id=1`) rather than per-job input.

### The Playwright MCP fallback: designed, not built

Task 0 of the implementation plan existed to gate on whether `claude -p --chrome` could drive a
browser **non-interactively** at all — that was unverified going in, since the documented
`--chrome` usage is interactive. The probe above succeeded, so the fallback below was never
needed and was never built:

```
claude -p --mcp-config <playwright.json> --strict-mcp-config --allowedTools "mcp__playwright__*"
```

with `@playwright/mcp` as the MCP server and a **persistent profile** under
`data/browser-profile/` (so cookies/sessions on the board's own site survive between runs, the way
a real browser profile would). Only `ApplyCliOptions` and the prompt's tool vocabulary would
change if this is ever needed — same `ApplyOrchestrator`, same schema, same per-job-call
architecture. Reach for it only if a future macOS/Chrome update breaks the extension handshake in
a way that isn't a one-time fix.


### Observability and resumable sessions (added 2026-09-17, same evening)

**The failure that forced this.** The first real batch ran its first job (a Greenhouse posting) for
202 seconds, the user watched Claude stall at the resume-upload step, and cancelled. The
`job_application` row then said `failed / cancelled` and **nothing else** — the call used
`--output-format json`, which emits one blob at process exit, and the kill discarded it. The tabs
Claude had opened closed with the session. There was no way to learn what it had done, let alone
why. Two changes, both verified with probes before building:

- **Stream, don't buffer.** The apply call now runs `--output-format stream-json --verbose`
  (`--verbose` is required with stream-json in print mode), one JSON object per stdout line:
  `system/init`, `assistant` (text and `tool_use` blocks), `user` (`tool_result` blocks, with
  `is_error`), assorted `rate_limit_event`s, and finally `result` carrying `structured_output`,
  `total_cost_usd` and `session_id`. `ClaudeCliClient.runStreaming` reads stdout line by line and
  hands each event to a listener; `ApplyOrchestrator`'s listener writes a timestamped line to
  `logs/apply/batch-<b>-job-<jobId>.log`, mirrors tool calls into `logs/apply.log`, and stores the
  latest one on `job_application.last_activity` (V13), which the Results tab shows under the live
  progress line and in a per-job **Details** list with a **View log** link
  (`GET /api/applications/{batchId}/jobs/{applicationId}/log`). The scan path (§9) is untouched -
  it still uses the buffered `runStructured`.
- **Idle watchdog, not just a deadline.** `apply.idle-timeout` (3m): a job with no stream event for
  that long is killed and marked `failed` with a "Stuck:" note. The 10-minute `apply.timeout` was
  the only guard before, and a page frozen by a native file dialog would have sat there for all of
  it. The most likely cause of the original stall is exactly that - a button labelled Attach /
  Browse / Choose file that opens the macOS file picker, which blocks the page and every extension
  command with it (the Chrome docs' "modal dialog" case). The prompt now says to use the upload tool
  on the `<input type=file>` and never click a button that opens the OS picker.
- **Every job is a resumable session.** `--no-session-persistence` is gone from the apply call and
  each job gets `--session-id <uuid>`, stored on the row. Verified: a `-p` session started that way
  can be continued with `claude --resume <uuid> --chrome`, and the resumed session remembered what
  it had done ("I opened boards.greenhouse.io/airbnb, which redirected to…", $0.11 for the question).
  The UI shows that command with a Copy button, so "interact with Claude to see why it is stuck"
  is literally that: open a terminal, paste, ask. The browser tab group is gone by then (the CLI
  closes it when the session ends) but the session can reopen the posting.

**Privacy line, restated.** `logs/apply.log` keeps the §9 discipline (no prompt, resume or profile).
The per-job transcripts under `logs/apply/` deliberately do **not** - they record the tool inputs,
which include whatever Claude typed into the form, because a transcript without the typed values
cannot explain a stuck form. `logs/` is gitignored; say so in any doc that points people at them.

### The first real transcript: forms inside cross-origin iframes (2026-09-20)

With streaming in place the original stall reproduced in full view (batch 2, `logs/apply/`,
$0.88): Claude reached Stripe's application page, filled name / email / phone / location, and then
could not find the resume field - **Stripe embeds the Greenhouse form in a cross-origin iframe**,
and the extension's `find` / `read_page` only see the host page's accessibility tree. No ref for
the `<input type=file>` means the upload tool has nothing to target, and the one visible route (the
"Attach" button) opens the macOS file picker, which the prompt forbids. It stopped cleanly with a
precise `summary`, which is exactly the behaviour the observability work was for.

**The fix is a URL, not a browser trick.** Greenhouse serves every board's form as a standalone,
iframe-free page at `https://job-boards.greenhouse.io/embed/job_app?for=<slug>&token=<jobId>`
(verified for Stripe: 200, `id="resume" type="file"`, "Submit application" - even though Stripe
redirects the *hosted* board URL `job-boards.greenhouse.io/stripe/jobs/<id>` back to its own site).
Lever has `<jobUrl>/apply` (verified). `apply/ApplyUrlResolver` computes this **direct form URL**
per job and the prompt tells Claude to open it first, falling back to the posting's own Apply link
only if it fails. The Greenhouse slug comes from `jobUrl` when it is a hosted board URL, otherwise
from `ats_company` by company name (`AtsCompanyRepository.findByAtsAndCompany`; every Greenhouse
company in the database matched, 23/23). Workday has no such URL - see the limitation below.

Two smaller findings from the same transcript, now prompt rules: the extension's `type` action
sometimes does not register (Claude had to fall back to `key`, and then to re-verify each field),
so the prompt says to prefer element refs over coordinates and to confirm each typed value; and
the transcript's timestamps are now local time, like `apply.log`, after the first one came out in
UTC next to a local-time log.

**Known limitation: Workday requires a candidate account.** Four of the five jobs in the user's
first batch were Workday postings. Workday's apply flow ("Apply Manually") sits behind a sign-in /
create-account wall per tenant. The prompt tells Claude to stop with `failed / "Workday account
required"` rather than create accounts on the user's behalf. Supporting Workday properly means
deciding whether the applicant profile should hold a password for those accounts and whether
Claude may create them - a product decision, deliberately not made here.

### Second transcript: the upload works, then the laptop went to sleep (2026-09-20)

Batch 3 - same Stripe posting, now sent to the direct Greenhouse form URL - **uploaded the resume
on the 12th action** (`file_upload` on `ref_745`, "Uploaded 1 file(s) to file input: 3.pdf"),
handled Greenhouse re-rendering the country dropdown after the upload, filled school / degree /
residence, and answered the work-authorization and sponsorship questions from the profile. Then
it ended `error_max_turns` at 60 actions with the form about two-thirds done, and the row said
"claude CLI reported an error" at $0.00. Three things learned:

- **`apply.max-turns: 60` was a guess and a real form needs more.** Now 150, with
  `max-budget-usd` raised to 4.0 to match. A max-turns or max-budget ending is now reported as
  what it is, keeps the CLI-reported cost (`CliJsonResult.Failed` gained a `costUsd`), and the
  row's notes carry the `claude --resume <sessionId> --chrome` command - a partly filled form is
  the best possible case for resuming the session and telling Claude to keep going.
- **A 1355-second gap between two actions was the Mac sleeping, not a hung run.** `apply.log`
  timed the job at 272 s of process time while the transcript spans 26 minutes of wall clock:
  `System.nanoTime()` on macOS does not advance during sleep, so neither the 10-minute deadline
  nor the 3-minute idle watchdog could fire, and the first action after waking failed with
  "Couldn't determine which page this action targets" until the extension re-found its tab.
  `ApplyOrchestrator` now runs `/usr/bin/caffeinate -i` for the lifetime of a batch (idle sleep
  only; the display may still sleep). The timeouts are deliberately left on monotonic time - a
  sleeping laptop should pause a job, not fail it.
- **The extension's `type` action is unreliable on this form; `key` was not.** Already a prompt
  rule ("confirm each typed value") since batch 2; batch 3 did exactly that and lost no fields.

### Why an application took 66 browser actions, and the plan-first prompt (2026-09-20)

Batch 4's transcript (the first complete `needs_review`, $1.40, 4 min 10 s) shows where the time
went: **15 screenshots, 15 scrolls, 18 coordinate clicks, 8 batched actions, 3 raw `type`s - and
exactly one `read_page` and zero `form_input` calls**, plus 38 narration turns, at 3.8 s per turn.
Claude drove the form the way a person with a mouse would: scroll, screenshot, click the field,
type, screenshot to check, scroll again. Each of those is a model turn with its own API round-trip,
so the cost is turns, not typing.

The fix is structural, in two parts:

- **Read the questions before opening the browser.** Greenhouse's direct form page is fully
  server-rendered (labels, selects with their options, `required` markers), and Lever's `/apply`
  page carries `.application-label`s. `apply/FormQuestionPrefetcher` GETs the direct form URL
  with jsoup and hands the prompt a `FORM QUESTIONS` list; unit-tested against saved fixtures
  (`fixtures/greenhouse_embed_stripe.html`, `fixtures/lever_apply_shieldai.html`), never live.
  The live page stays authoritative - dynamic questions can still appear.
- **Plan first, fill by reference, batch.** The prompt now mandates: one `read_page` to enumerate
  fields and refs, ONE message with the complete plan (every field → value or "blank
  (unanswered)"), then `form_input` by ref in `browser_batch`es of up to eight, `file_upload` on
  the file-input ref, click/type/screenshot only for widgets `form_input` cannot set, one
  `read_page` verification pass, no screenshots after routine actions and no narration between
  them. The extension itself nags "Prefer browser_batch" on every single-call turn - the
  transcripts are full of that reminder being ignored because the prompt never asked for it.

### Questions Claude cannot answer become a table, and every batch writes a report

The profile's free-text "how to answer anything else" box is replaced by `profile_answer` (V14):
one row per question, keyed on `QuestionKey.normalize` (lowercase, whitespace-collapsed, trailing
`* ? :` stripped) so "Do you opt-in to receive WhatsApp messages? *" and its unstarred twin are one
row. When a job's structured result lists `unanswered` questions, `ApplyOrchestrator` records each
as `pending` (or bumps `asked_count` / `last_company` on an existing row - an answered row is never
flipped back), the job still ends `needs_review` with its tab open, and the batch moves on. Answered
rows go into the prompt as `KNOWN ANSWERS`, pending ones as "still unanswered, leave blank"; so
the loop closes the first time the user fills in an answer on the Resumes tab. Pre-existing free
text is migrated into a single "General notes" row - nothing typed there is lost.

At batch end the orchestrator writes `logs/apply/batch-<id>-report.md` (jobs, outcomes, cost,
which tabs were left open, the `claude --resume` command per job, and the new pending questions),
stores its path and the new-question count on `apply_batch`, and the Results tab shows it as the
end-of-run report with a pointer to the Resumes tab. `GET /api/applications/{id}/report` serves it.

**Measured, same Stripe form, submit off (2026-09-20):**

| Run | Prompt | Actions | Screenshots | Batched calls | Wall clock | Cost |
|---|---|---|---|---|---|---|
| batch 4 | original | 66 | 15 | 8 | 4:10 | $1.40 |
| batch 5 | plan-first + pre-read questions | 79 | 18 | 2 | 4:53 | $1.67 |
| batch 6 | + one-batch keyboard technique per dropdown, screenshot ban, `--effort medium` | **53** | **5** | 21 | **4:01** | **$1.24** |

Plan-first alone did not help: the transcript shows `form_input` set all six text fields in one
batched call (68 s from page open to text fields done, upload included) and then **eight custom
dropdown widgets** (School, Degree, country of residence, phone country, three Yes/No selects,
the location autocomplete) ate three and a half minutes - School alone was 68 s of "type slowly",
scroll the list, screenshot, click. `form_input` cannot set these; the cure is the keyboard: click
the control, type the option text, Enter, in ONE `browser_batch`, verify by reading the field back.
That, plus banning screenshots except the final one, is batch 6. The remaining floor is one model
turn per widget plus the page reads; the ~4 s per turn is API latency, hence `apply.effort`
(default `medium`, passed as `--effort`) as the last lever - `low` is faster and untested for
form-filling judgment.

**Question keys must ignore the model's annotations.** Batch 6 recorded "Gender (voluntary EEO)"
next to batch 5's "Gender": the prompt asks for the label's exact text, but the model still adds
parentheticals. `QuestionKey.normalize` now strips trailing parenthetical groups, and the prompt
says no annotations - both, because one of them will be ignored again.

### Four fixes from batch 9 (2026-09-23)

Batch 9 (42 recommended rows, the first batch run over LinkedIn rows resolved by §13) surfaced
four problems in the first four jobs. Each has a test; the reasoning is here.

- **The employer saw "3.pdf".** `ResumeService` stores an upload under its row id
  (`data/resumes/3.pdf`) on purpose - the original filename is user-controlled and could carry
  path separators - and the apply prompt passed that stored path straight to `file_upload`, so
  every ATS received a resume named `3.pdf` (the Zip and Glean summaries both say "uploaded
  3.pdf"). The stored file stays id-named; `resume/ResumeUploadFile.forUpload` now hands the
  orchestrator a copy under `data/resumes/named/<id>/<original filename>` (sanitised to one path
  segment, spaces kept, refreshed when the stored file changes), and `ResumeService.delete`
  removes that copy. If the copy cannot be made the stored path is used, so a batch never fails
  over a filename.
- **"Failed to start applying." on every click, then 409 on the next.** `POST /api/applications`
  answers `202 Accepted` *with* a `{batchId}` body, but `request()` in `api/client.ts`
  returned `undefined` for every 202 (written for the body-less cancel endpoint), so
  `const { batchId } = ...` threw a TypeError that the catch rendered as the generic error - while
  the batch had in fact started. The next click then hit the real 409. Present since commit
  40fd09e; every batch 4-9 was started this way. A 202 is now parsed when it has a body.
- **A restart mid-batch wedged the feature.** Batch 9 was restarted after job 3 of 42; the
  virtual thread died, the `apply_batch` row stayed `running`, and from then on every start
  was a 409, the Results tab re-attached to "Applying 3/42" forever, and Cancel was a no-op
  (`ApplyOrchestrator.cancel` only knows the in-memory batch). Same failure §7's
  `OrphanedRunReaper` fixes for runs, same fix: `apply/OrphanedApplyBatchReaper` runs at
  startup and closes every `running` batch this process is not running as `interrupted`
  (its `queued`/`filling` jobs become `failed` with an explanatory note; spent cost is kept),
  and `POST /api/applications/{id}/cancel` does the same as `cancelled` when asked to cancel a
  batch that is `running` only in the database. The 409 message now names the batch and says
  to cancel it. `interrupted` joins the `ApplyBatchResponse.status` union in `types/api.ts`.
- **Stripe: "form in cross-origin iframe, no direct URL".** §13's resolver stores whatever
  LinkedIn pointed at; for Stripe that is `stripe.com/careers/listing/<slug>/8062305?gh_src=...`,
  `apply_domain=stripe.com`, which `ApplyUrlResolver` did not recognise, so no DIRECT APPLY
  FORM URL - and Stripe's page renders the Greenhouse form in the cross-origin iframe §12
  diagnosed on day one. The page itself names the form, though: a `<noscript><iframe
  src="https://job-boards.greenhouse.io/embed/job_app?for=stripe&amp;token=8062305">` (verified
  with curl 2026-09-23). `apply/EmbeddedFormDetector` GETs a company-site apply link once
  (10 s timeout, 2 MB cap, best-effort like `FormQuestionPrefetcher`) and looks for that URL, or
  Greenhouse's `embed/job_board/js?for=<slug>` loader plus a job id (`gh_jid`,
  `Grnhse.Iframe.load(id)`, or a 6+ digit run in the page URL's path), or a hosted Lever
  posting URL, and the resolver uses the result exactly as it would a native Greenhouse row's
  embed URL - which also switches on the server-side question pre-read. Workday links and
  anything the resolver already recognises are never fetched. Anything with no embedded form
  still resolves to empty and Claude finds the form itself as before.

**Similar questions were being left blank.** Not a code bug but a prompt one: KNOWN ANSWERS
said "use these verbatim when a form question matches", and the model read "matches" as
"is worded the same" - so "Do you opt-in to receive WhatsApp messages" (answered) did nothing
for "Indicate your agreement to receive text message updates", which was recorded as a new
pending question instead. Step 4 of the prompt now says a known answer matches by *meaning*,
gives the SMS/WhatsApp and hybrid-schedule examples, and explains that `{company name}` in a
saved question stands for the company being applied to (the user's own answers already use
that placeholder). Only a question no known answer covers counts as unanswered. Watch the next
batch's "new questions" count: if near-duplicates still appear, the next lever is answering
them once in the Resumes tab - `QuestionKey` deliberately does not try to guess synonyms.

### Batch 10, the first full run (42 jobs, 2026-09-23 evening)

17 needs-review, 25 failed, 27 new pending questions, $-cost per the report. What the failures
taught, and what changed:

- **"Apply failed" on a form that was 90% filled.** Evlo, Realtor.com and others stopped on a
  required Zip Code / Street Address / travel-percentage field because the prompt's blocker list
  said "an unavoidable required field with no answer available → failed". The questions *were*
  recorded (that part was never broken - "zip code" reached `asked_count` 4), but the job wore
  the wrong badge and the user read it as "no longer adding questions". The prompt now says a
  required field with no answer is not a failure: leave it blank, list it, leave the tab open on
  that step, outcome `needs_review`. `failed` is reserved for a login wall, an account-creation
  gate, or a CAPTCHA.
- **Enter submitted a form with submit off.** Glean (`job_application` 61): Claude pressed
  Return to confirm the last custom dropdown (Veteran Status), the list was already closed, and
  Greenhouse treated it as form submit - the application went out. The row is `needs_review` in
  the DB because the CLI reported it that way; the notes say INCIDENT. The dropdown rule now says
  press Enter only while the option list is visibly open, and click the option when unsure.
- **Workday: the Bitwarden route was never going to work as written.** Six Workday tenants
  (Salesforce ×2, Disney, Nasdaq, AssetMark, Dow Jones, Blue Origin) all ended the same way:
  Claude clicked the email field, saw no inline menu, pressed Cmd+Shift+L, nothing. Two reasons.
  A keystroke sent through the Claude-in-Chrome extension is dispatched into the *page*;
  extension command shortcuts are handled by the browser before any page sees them, so another
  extension's shortcut can never be triggered this way. And Bitwarden's inline menu appears on a
  real click only when the vault has an item whose URI matches - and Workday accounts are per
  tenant (`assetmark.wd5.myworkdayjobs.com` is a different site from `nasdaq.wd1...`), so "an
  account in Bitwarden" covers at most one of them. The prompt now drops the shortcut, tries the
  inline menu exactly once, and fails with `Workday: no Bitwarden item for <tenant host>` so the
  transcript names the tenant that needs a vault item. **An attempt to add a stored Workday
  email/password to the applicant profile (V16, a prompt section, redaction in the transcript)
  was blocked by the Claude Code permission classifier as credential leakage and was not
  built** - the migration was never written; nothing of it is in the tree. If that route is
  wanted, it is a deliberate reversal of the "never type a password" rule above and should be
  the user's explicit decision.
- **Stripe and ZoomInfo still had no direct form URL** despite `EmbeddedFormDetector`. Two
  separate causes, both fixed: over HTTP/2, `stripe.com` serves a variant of the listing page
  without the `<noscript>` Greenhouse iframe (curl, HTTP/1.1, gets it), so the detector's client
  is pinned to HTTP/1.1; and `zoominfo.com/careers?gh_jid=…` answers 403 to any non-browser
  client, so nothing can be read from the page at all. The detector therefore has a second step:
  a job id from the URL (`gh_jid`, a 6+ digit path segment) or page (`greenhouseId`), plus the
  site's own name as a slug guess (`stripe`, `zoominfo`), confirmed against Greenhouse's public
  Job Board API (`boards-api.greenhouse.io/v1/boards/<slug>/jobs/<id>`, 200 or 404) before it is
  trusted. Both real cases confirm (verified with curl 2026-09-23).

### Apply tab, parallel batches, and the Bitwarden decision (2026-09-23, night)

Three asks from the user: a tab where they paste URLs and Claude applies to them, several
applications at once instead of one at a time, and Workday sign-in from the accounts they keep in
Bitwarden.

**Parallel applications - probed before building.** Two `claude -p --chrome` processes started at
the same moment (read-only probe, two Workday sign-in pages) both finished in 31 s, $0.37 in total,
each in its own Chrome tab group (`tabGroupId` 699715399 and 1424052738). The Chrome native host
owns one Unix socket (`/tmp/claude-mcp-browser-bridge-<user>/<pid>.sock`) and every
`claude --chrome` process connects to it as a client, so nothing in the extension serializes
sessions. **This supersedes "One CLI call per job, sequential" above**: it is still one CLI call
and one session per job - the blast-radius argument is unchanged - but a batch now runs up to
`apply.concurrency` (default 3) of them at once; a request may ask for 1-5
(`ApplyOrchestrator.MAX_CONCURRENCY`). Only two at once has been observed live; watch the first
batch at 3. How it is built:

- `runBatch` starts that many virtual-thread workers, each taking the next job from a `JobQueue`
  in batch order. **Two Workday jobs on the same tenant host never run side by side** (the queue
  passes over a job whose tenant another worker holds; `ApplyOrchestrator.exclusiveKey`). They
  would share one signed-in session and account, and a second tab signing in under a half-filled
  form is not worth finding out about - a precaution, not a failure seen in a transcript.
- Results land through one lock (`BatchTally`), which also writes the `apply_batch` progress row.
  `ProfileAnswerRepository.recordUnanswered` reads then inserts, so two jobs finishing with the
  same new question would both insert it - that bookkeeping runs under `questionLock`. The
  named resume copy (`ResumeUploadFile.forUpload`) is made once per batch so workers never race
  refreshing it. The report lists jobs in batch order, not finish order.
- A batch's terminal status is now `cancelled` whenever the cancel flag is set at the end; before,
  a cancel that landed during the last job still finished `ok`.
- The UI shows every job currently filling, each with Claude's last action
  (`ApplyBatchView.ApplyBatchStatus`).

**The Apply tab.** `POST /api/applications/urls {urls, resumeId?, submit?, concurrency?}` turns each
URL into a `job_listing` row with `source='pasted'` (`apply/PastedUrlJobs`) and starts an ordinary
batch over them. A pasted row is shaped exactly like a LinkedIn row §13's resolver has resolved -
`apply_url` = the URL, `apply_domain` by host (`LinkedInApplyLinkResolver.domainOf`) - so
`ApplyUrlResolver` rewrites a Greenhouse link to its embed form and a Lever link to `/apply`, and
hands a company careers page to `EmbeddedFormDetector`, without a pasted-specific branch beyond
the `case`. Deliberate details:

- `source_job_id` is the URL itself, so pasting the same URL again reuses its row.
- `last_seen_run_id = 0` (no run saw it), and **`FilterEngine` skips `source = 'pasted'`**
  (`findWithoutVerdict`, `findWithFilterVersionLessThan`): its `filter_verdict` stays null, so it
  can never appear in the Results triage views, which all read `filter_verdict = 'pass'`. The
  company-volume report skips it too. A pasted job Claude submits is marked `applied` and appears
  on the Applied tab like any other.
- Title and company are unknown until Claude reads the page: the row starts as
  "(title not read yet)" plus a company guessed from the URL (board slug, Workday tenant, or host),
  the prompt tells Claude to read all three off the page instead, and the apply schema grew
  optional `jobTitle` / `company`, which `setPastedTitleAndCompany` writes back - its `WHERE`
  includes `source = 'pasted'`, so a real source's title can never be overwritten.
- Rows are written only after every guard (profile, resume, URL parse, concurrency, 409, CLI)
  has passed, so a rejected request leaves nothing behind.

**Workday sign-in: the user chose Bitwarden autofill, not the Bitwarden CLI.** The alternative put
to them was the `bw` CLI: the backend looks up the login whose URI matches the tenant and Claude
types it. That works whatever the extension's settings are, but the password would pass through
the model's context (sent to Anthropic, and kept in the CLI session file `--resume` reads).
Autofill keeps the password inside Bitwarden - it goes from the vault straight into the page, and
Claude only chooses which login.

The same probe as above answered why batch 10's six Workday jobs saw nothing. On both sign-in
pages, a click in the Email Address field (by element ref on one, by coordinates on the other)
produced **no Bitwarden UI at all** - not the login list, not "No items to show", not the
unlock prompt - while Bitwarden 2026.8.0 is installed and enabled in the Chrome profile Claude
drives. When the inline menu is on, Bitwarden shows *something* on focus even for a locked vault
or a site with no login, so the likely cause is that **the inline menu is switched off**
(Bitwarden → Settings → Autofill → "Show autofill suggestions on form fields"), or the extension
is logged out - not the per-tenant URI mismatch batch 10's note guessed at. The prompt's Workday
section is rewritten for the autofill route:

- The menu is drawn in an extension iframe that `find`/`read_page` cannot see, so Claude reads it
  from one screenshot and clicks by coordinates.
- Claude picks the login whose name or website matches the tenant (with Bitwarden's default
  base-domain matching, a login saved for one `*.myworkdayjobs.com` tenant may be offered on all
  of them), and never tries a second one - a wrong password can lock the Workday account.
- It confirms the fill by reading `.value.length` of the password field, never the value.
- Each way the sign-in can fail has its own summary, so the row says what to fix: vault locked,
  no login for `<tenant host>`, menu never appeared (turn the setting on), or Workday's own
  rejection quoted.

**Still unverified end to end**: nobody has watched Claude sign in through the menu yet. It needs
the setting on, the vault unlocked, and a login saved for a tenant; the first real Workday
transcript is the test. Re-run the two-page probe above (scratch prompt: open a tenant's
`/apply/applyManually` URL, click Sign In, click the email field, screenshot, report) after
turning the setting on - it costs about $0.20 and settles whether the menu appears to a CDP click.

## 13. LinkedIn postings → their real apply link (2026-09-21) - REMOVED 2026-09-23

**Removed.** On 2026-09-23 LinkedIn banned the burner account this section's resolver signed in
with, for suspicious activity. It had read about 40 postings per run across runs 29-31, and in
run 31 it was signed out mid-phase after ~34 postings in about 3 minutes, shortly before the ban.
At the user's request the whole signed-in step is gone: `LinkedInApplyLinkResolver`,
`LinkedInLinkProperties`, the `apply.linkedin-links` config block, run phase 6 and its `matching`
status (removed from `RunController.IN_FLIGHT_STATUSES`, `runStatus.ts` and the `RunStatus`
type), the Re-scan hook that ran it, and the repository methods only it used
(`findLinkedInUnmatchedByRun`, `setApplyMatchNote`, `setApplyKind`). **The lesson for §2's rule:
even read-only, never-click automation on a signed-in LinkedIn account gets that account banned,
burner or not - do not bring back anything that signs in to LinkedIn.** The user now opens LinkedIn
postings by hand and pastes the company's apply URL into the Apply tab (§12, "Apply tab, parallel
batches"). Links the resolver had already written stay on their rows (`apply_url`/`apply_domain`)
and Apply with Claude still uses them - that is company-site data, and using it touches no LinkedIn
page. Everything below is the history of what was built and why.

**§12 said LinkedIn rows are skipped because applying through LinkedIn needs the user's own
logged-in account - that rule is untouched.** What changed is that most recommended jobs come
from LinkedIn (92 of 97 in the bucket this was built against), and every one of them used to end
"Apply manually" even when the posting itself is really just a redirect to the company's own
careers site. This section reads that redirect's real destination straight off the LinkedIn
posting page - no catalog, no cross-referencing, no LinkedIn account exposed to anything beyond a
browser tab a human is watching.

### The redirect route is closed for a guest - verified before building anything else

The obvious shortcut - read the offsite posting's destination URL off the page and send Claude
there - was checked live first (two requests, budget at 0/1000, before spending any implementation
effort) and does not exist for an unauthenticated request. The guest fragment the app already
fetches (`/jobs-guest/jobs/api/jobPosting/{id}`) has a sign-in-modal Apply button with no `href`
(§2 already said so), and the public `/jobs/view/{id}` page - checked on an onsite posting
(4296091740) and an offsite one (4356164535), ~300 KB each - carries no `applyUrl` or
`externalApply` element either, only the `joinUrlWithRedirect` sign-up link. That redirect is only
reachable with a logged-in session - which is exactly why the two approaches below exist, first a
catalog match that avoided a logged-in session entirely, then (once that was judged too weak) a
resolver that accepts needing one, on a burner account. See below.

### The apply-kind marker, free from the same fragment

The fragment does carry one useful, free signal: whether the LinkedIn posting itself is Easy
Apply. `source.linkedin.DetailParser.applyKindOf` reads it two ways - `onsite` when an Apply
button's `data-tracking-control-name` equals `public_jobs_apply-link-onsite`; `offsite` when any
`div.contextual-sign-in-modal`'s `data-impression-id` starts with
`public_jobs_apply-link-offsite` (matched as a prefix, not an exact modal - unrelated sign-in
modals for save/AI-summary/etc. coexist in the same fragment). Stored as `job_listing.apply_kind`
(`V15__linkedin_apply_target.sql`). **It is informational only - nothing downstream filters on
it except to skip the resolver entirely for `onsite` rows**, since an Easy Apply posting has no
redirect to read in the first place. Its only other consumer is the UI's muted "Easy Apply" tag
on a row that stays unresolved, so the user knows a redirect was never possible rather than
wondering why nothing showed up.

### What was built first, and why it was removed

The first approach, built and shipped the same day, never sent LinkedIn a request at all: cross-
reference each recommended LinkedIn posting against the `ats_company` catalog by exact company
name, then score it against that company's actual Greenhouse/Lever/Workday postings
(`apply/LinkedInAtsMatcher`, `apply/PostingMatcher`, `apply/LinkedInMatchProperties` - requisition
id first, then Dice description similarity with a margin requirement, then a title/location tie-
break, then a single-candidate fallback below the text threshold). It worked exactly as designed
and was still not good enough: the first live measurement (run 29, after a Re-scan) matched only
**11 of the 39** recommended LinkedIn jobs. The other 28 failed at the very first step, company
lookup, because the company simply isn't in the `ats_company` catalog - Deloitte, JPMorgan, AWS,
Google and Walmart among them, none of which run a Greenhouse/Lever/Workday board at all, or run
one under a name/slug this project has never imported. No amount of tuning the scoring thresholds
fixes a company that was never in the catalog to begin with, and the user judged an 11/39 hit rate
too low to be worth the matcher's complexity. All three classes were deleted, along with the
`apply.linkedin-match` config block and the `SourceQuery.maxDetails`/`SourcedJob.requisitionId`
adapter additions that existed only to feed it.

**One thing the matcher surfaced stayed, because it was a real bug unrelated to matching:**
`LeverJobSource` only ever read a posting's `descriptionPlain` field, which Lever fills with just
the intro (119 of 490 words on the Wealthfront posting that exposed this) - `lists` (every
Responsibilities/Requirements section, as HTML) and `additionalPlain` (the closing) were both
being silently dropped. Against the concatenation of all three the Wealthfront posting scored
1.00 similarity against itself instead of 0.40. `LeverJobSource.fullDescription` now joins all
three in page order (`LeverJobSourceTest.descriptionConcatenatesIntroListSectionsAndClosingText`).
This had also been silently starving the AI scan (§9) of three quarters of every Lever posting's
text since it was built - verdicts already cached in `ai_match` for Lever jobs predate the fix and
won't be re-scored until a new resume or a targeted rescan.

### The replacement: reading the real apply link out of a signed-in Chrome

`apply/LinkedInApplyLinkResolver` (`@Service`) runs in the matcher's old slot - after the scan, in
both the run and resume paths, and after a manual Re-scan - and, for the run's recommended
LinkedIn rows that still have no apply link (skipping rows already known `apply_kind='onsite'`,
i.e. Easy Apply), drives the same Claude-in-Chrome CLI invocation Apply with Claude uses
(`apply/ApplyOrchestrator.chromeArgs`) in batches of `apply.linkedin-links.batch-size` (8)
postings per call, every candidate in the run; each batch is its own session with a transcript
at `logs/apply/links-run-<run>-batch-<n>.log`. **There is no per-run cap.** One (`max-per-run: 40`)
was added here unasked and removed on 2026-09-23 at the user's request: run 31 had 117 candidates,
resolved 26, and left 77 untouched as "link cap reached - retried next run".

**The probe result, verbatim, that made this viable:** on a signed-in `/jobs/view/{id}` page the
Apply button's `href` is `https://www.linkedin.com/safety/go/?url=<url-encoded destination>` -
Claude reads it and URL-decodes it with the javascript tool, **without clicking it**. Measured on
2 postings: 21 turns, $0.30, both external - an iHeartMedia Workday `…/apply?source=Linkedin` URL
and a jobbol.com.br vacancy. Easy Apply postings have no such button at all, so they're recognized
by the existing onsite/offsite marker above and never sent to Chrome.

**This needs a signed-in LinkedIn session, which §2's rule was written to prevent exposing to
automation - so it runs against a burner account, never the user's own.** That's the user's
explicit choice, made the same day the matcher was removed: read-only, never-click behavior on a
throwaway account is judged an acceptable trade for a real hit rate, where the matcher's zero-
LinkedIn-traffic guarantee bought only 11/39. Nothing about §2's protection of the *real* account
changes, and nothing is ever submitted, applied to, or changed on any account, burner included -
the resolver only reads and decodes a redirect.

Results land on the row exactly where a matcher match used to: `apply_url`, `apply_domain`
(`greenhouse`/`lever`/`workday` by host, otherwise the destination host itself, e.g.
`jobbol.com.br`), `apply_kind='offsite'`, `apply_match_note` "linkedin apply link (href)" -
`apply/ApplyUrlResolver` then turns a Greenhouse hosted URL into the iframe-free embed form and a
Lever URL into `/apply`, same as any native ATS row; other hosts are opened as-is. **If the page
turns out to be a sign-in wall**, the phase stops outright and every row still unresolved that run
gets the note "LinkedIn is not signed in in Chrome - sign in to the burner account and Re-scan" -
a manual Re-scan afterward re-runs this phase against whichever recommended rows still have no
apply link, the same as it always did for the matcher. A CLI failure on one batch notes that batch
and continues to the next; a missing CLI binary or a disconnected extension stops the whole phase.

**Cost:** roughly $0.15 per posting resolved one at a time; batches of 8 bring that down because
the ~25k-token fixed overhead per `claude` invocation (§9) is paid once per batch, not once per
posting, so a run with ~120 candidates costs about $7. Config: `apply.linkedin-links: enabled,
batch-size 8, max-turns 120, max-budget-usd 2.0, timeout 10m` (the last three are per batch).
Run 31 also showed the other limit: LinkedIn signed the burner account out after ~34 postings in
about 3 minutes, and the phase stops at a sign-in wall - sign back in and Re-scan to continue.

### Where the `matching` phase sits in the run, and why it had to touch the scanning checklist

`RunOrchestrator` runs the resolver as phase 6, after the AI resume scan and before `finishRun`,
in both `executeRun` (a normal run) and `executeResume` (the §10 Retry-after-cooldown path also
scans, so it also resolves) - gated on `apply.linkedin-links.enabled` and skipped on cancellation,
wrapped in the same try/catch idiom as `fetchDetailsSafely`/`classifyLocations` so a resolver
failure can never turn a successful run into a failed one. It publishes the same in-flight status
the matcher used, `matching`, which - like every in-flight status before it - had to be added to
the **entire** `scanning` checklist, not just the obvious spot: `RunController.IN_FLIGHT_STATUSES`,
`SweepProgress`'s javadoc, and on the frontend both `runStatus.ts`'s `IN_FLIGHT_STATUSES` *and*
`RUN_STATUS_INFO` (missing the second one means the UI falls through to a raw "Run ended with
status matching" instead of a friendly label) plus `types/api.ts`'s `RunStatus` union. This
checklist cost was already paid when the matcher first added the status, so the resolver reused
the name rather than adding a second one for what is, from the run's perspective, the same slot.
`MatchController.scan`'s manual **Re-scan** path (the `runId` branch) calls the resolver the same
way after its scan completes; the `jobIds` rescan branch (re-scoring specific jobs, no run in
scope) skips it - there's no `runId` to resolve against, and that's documented in a comment rather
than left silent.

### Open items

- **Bitwarden autofill is unverified against a live Workday tenant.** Task 0 of the plan called
  for probing `claude -p --chrome` against a real Workday sign-in page with a saved Bitwarden
  login, vault unlocked, to see whether the inline autofill menu or Cmd+Shift+L actually reaches
  Bitwarden's extension UI (which is not page DOM, so whether the Chrome extension's tools can
  even see it was an open question). That probe needs a tenant the user has a login for and has
  not been run. The prompt rule (§5 in the plan) ships untested; treat the Workday apply path the
  same way §12 treats a from-scratch Workday account today - watch the first real transcript
  before trusting it. Unaffected by the matcher's removal: a Workday `apply_url` can now arrive
  via the resolver above just as it used to arrive via a match, and either way it reaches the same
  untested Bitwarden prompt rule in `ApplyPromptBuilder`. **Update 2026-09-23 night:** the probe was
  run (§12, "Apply tab, parallel batches, and the Bitwarden decision") - no Bitwarden menu appears
  at all on a Workday email field, most likely because the inline menu is switched off; the prompt
  rule was rewritten, and the sign-in itself is still unwatched.

**First live run of the resolver (run 29, Re-scan, 2026-09-21):** candidates=39, external=32,
easy-apply=2, closed=5, unresolved=0, 5 batches of 8, 172 browser actions in total, 705 s wall
clock, **$2.25**, and `request_log` unchanged at 1561 before and after - the backend made no
LinkedIn request; every read went through the signed-in Chrome. Every link came from the
`safety/go/?url=` href (no clicks were needed). What the 32 destinations look like: 2 Workday, 4
Greenhouse (plus one `grnh.se` short link that redirects to Greenhouse and is stored as its own
host), 6 `amazon.jobs`, 3 `jpmorganchase.contacthr.com`, two iCIMS tenants, and a long tail of
company career sites and tracking redirectors (`rr.jobsyn.org`, `ars2.equest.com`,
`click.appcast.io`). Redirectors are stored as given; Apply with Claude opens them and follows
wherever they land, and `ApplyUrlResolver` only rewrites hosts it recognises (Greenhouse, Lever).
The catalog matcher this replaced had found 11 of the same 39.

**`V15__linkedin_apply_target.sql`'s header still names `LinkedInAtsMatcher`, on purpose.** I
corrected that comment after the migration had already run against the user's database, and the
next boot failed Flyway validation ("Migration checksum mismatch for migration version 15") -
Flyway checksums the whole file, comments included. Reverting the two lines byte-for-byte fixed
it. The §5 rule "never hand-edit a shipped migration" covers comments too; explain history here,
not in the migration.
