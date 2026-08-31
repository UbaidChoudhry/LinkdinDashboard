# Salary enrichment setup

jobdash can attach a salary range (`salaryMin` / `salaryMax` / `salarySource`) to each
job it finds. Jobs with a known salary sort to the top of the list (highest first) and
the dashboard's **Min salary** filter becomes useful; jobs with no salary just sort to
the bottom.

**Every credential below is optional.** The LCA disclosure dataset needs no key at all
and gives company-specific wage data. The two APIs (Adzuna, h1bapi.com) are fallbacks
for when the LCA data has nothing for a given company/title. With nothing configured,
jobs simply get no salary and fall to the bottom of the list.

Sources, recorded in `salarySource`:

| `salarySource` | Source | Credential | What it gives |
| --- | --- | --- | --- |
| `lca` | US DOL H-1B LCA disclosure data (local import) | none | company-specific wages, no request limit |
| `adzuna` | Adzuna API | free key required | title + location estimate, ~1,000/month |
| `h1bapi` | h1bapi.com | **none** (key optional) | company-specific H-1B wages, 20/day free |

## Which file to edit

1. Copy the template: `cp .env.example .env`
2. Fill in the values you have in `.env`.
3. Run `./run.sh` — it sources `.env` automatically and exports the vars to the backend.

`.env` is gitignored and must never be committed. `.env.example` is the tracked template.

## Adzuna key

1. Go to https://developer.adzuna.com/ and sign up.
2. Verify your email.
3. Open the **Dashboard**.
4. Copy **Application ID** → `ADZUNA_APP_ID` in `.env`.
5. Copy **Application Keys** → `ADZUNA_APP_KEY` in `.env`.

Adzuna's free tier is a **monthly** allowance (~1,000 calls/month), so the backend enforces
**two** windows: `salary.daily-cap.adzuna` (40/day, stops one big run eating the month) and
`salary.monthly-cap.adzuna` (900 per rolling 30 days, the one that actually protects the
quota). Both are counted from the `external_request_log` table, so a restart never resets them.
If Adzuna publishes a different quota for your account, adjust `monthly-cap` to match.

## h1bapi.com — key is OPTIONAL

**You do not need to sign up for this one.** h1bapi.com's free tier works unauthenticated:

| Tier | Cost | Limit | Coverage |
| --- | --- | --- | --- |
| **Free** | $0 | **20 requests/day** | last 2 fiscal years |
| Dev | $9/mo | 5,000/day | all years |
| Pro | $29/mo | 25,000/day | all years |

The app calls the endpoint with no auth header when `H1BAPI_KEY` is blank, and attaches an
`X-API-Key` header only if you set one. So:

- **Leave `H1BAPI_KEY` empty** to use the free tier. Nothing else to do.
- **Set it** only if you buy a paid plan — then also raise `salary.daily-cap.h1bapi` to match.

`salary.daily-cap.h1bapi` defaults to **20**, exactly the free allowance. **Do not raise it
without a paid plan** — you would just generate rejected requests.

Because 20/day is tiny, this source sits **last** in the cascade and will normally be exhausted
early in a large run. It is a long-shot fallback; the local LCA dataset is the real workhorse
for company-specific data, and it has no limits at all.

## LCA disclosure data (primary source, no key)

1. Download the quarterly `.xlsx` files from
   https://www.dol.gov/agencies/eta/foreign-labor/performance — section
   **"LCA Programs (H-1B, H-1B1, E-3)"**.
2. Only **FY2023 or newer** is useful; data older than 3 years is discarded on import.
3. Drop them all into `data/lca/` (gitignored). No need to rename anything.
4. Stop the backend (`./stop.sh`), then import the whole folder in one go:
   ```
   ./import-lca.sh              # imports every .xlsx in data/lca
   ./import-lca.sh some/dir     # or point it somewhere else
   ./import-lca.sh --keep       # import but don't delete the spreadsheets
   ```
5. Re-run quarterly as new files are published.

**Spreadsheets are deleted once imported.** They are ~80 MB each and are of no further use
once aggregated into `lca_wage`. A file is only ever deleted when the import both succeeded
**and** kept at least one row — anything that fails, or that parses to zero rows (a changed
column layout, or a file entirely past the staleness cutoff), is left on disk and reported so
you can look at it. Pass `--keep` to disable deletion entirely.

Each file is ~80 MB / ~600k rows and imports in roughly **10–15 seconds**. A quarter typically
yields ~120k usable wage records across ~100k employer/occupation/state groups.

## Config knobs

In `application.yml` under `salary:`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | master on/off switch for salary enrichment |
| `cache-ttl` | `90d` | how long a looked-up salary is reused before re-checking |
| `max-data-age` | `1095d` (3 years) | data older than this is treated as no data |
| `pacing.min-delay` | `1s` | minimum delay between outbound API calls (all sources share one gate) |
| `daily-cap.adzuna` | `40` | max Adzuna calls per rolling 24h |
| `daily-cap.h1bapi` | `20` | max h1bapi.com calls per rolling 24h — **equals the free tier; don't raise without a paid plan** |
| `monthly-cap.adzuna` | `900` | max Adzuna calls per rolling 30 days (protects the ~1,000/mo free quota) |
| `monthly-cap.h1bapi` | `0` | `0` = no monthly limit (h1bapi bills per day, not per month) |

Every value is overridable by environment variable (e.g. `SALARY_ENABLED=false`,
`SALARY_CACHE_TTL=30d`).

> **Note on h1bapi.com:** the request/response shape was implemented from the public
> docs without a live key to test against. If h1bapi results never appear once a key is
> set, the endpoint or field names in `H1bApiSalarySource.java` may need a small
> adjustment — check the class's `NOTE:` comment.

## Verifying it works

After keys are set and at least one LCA file is imported, start a run. Within a minute,
passing jobs should begin showing salaries. The **Salary** column and the **Min salary**
filter on the dashboard then become useful.
