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

Test counts by suite are in [CODEMAP.md](CODEMAP.md). Run `./mvnw test` and expect **386
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
| Description | `content`, **HTML-entity-escaped** | `descriptionPlain` + real-HTML `description` | detail response only |
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

