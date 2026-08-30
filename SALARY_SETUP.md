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
| `lca` | US DOL H-1B LCA disclosure data (local import) | none | company-specific wages |
| `adzuna` | Adzuna API | free key | title + location estimate |
| `h1bapi` | h1bapi.com | free key | company-specific H-1B wages |

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

Free tier is roughly 1,000 calls/month. The backend rate-limits Adzuna and caps daily
usage at `salary.daily-cap.adzuna`, so the app stays within the free tier.

## h1bapi.com key

1. Go to https://h1bapi.com/ and sign up.
2. Get the API key from the account / dashboard page.
3. Set `H1BAPI_KEY` in `.env`.

It must stay within the free tier — the backend rate-limits it and caps daily calls at
`salary.daily-cap.h1bapi`. If the free tier is exhausted, the source is simply skipped.

## LCA disclosure data (primary source, no key)

1. Download the quarterly `.xlsx` files from
   https://www.dol.gov/agencies/eta/foreign-labor/performance — section
   **"LCA Programs (H-1B, H-1B1, E-3)"**.
2. Only **FY2023 or newer** is useful; data older than 3 years is discarded on import.
3. Put the files under `data/lca/` (gitignored).
4. Import each one:
   ```
   ./import-lca.sh data/lca/LCA_Disclosure_Data_FY2024_Q4.xlsx
   ```
5. Re-run quarterly to refresh as new files are published.

Each file is about 80 MB / ~600k rows; an import takes a few minutes.

## Config knobs

In `application.yml` under `salary:`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | master on/off switch for salary enrichment |
| `cache-ttl` | `90d` | how long a looked-up salary is reused before re-checking |
| `max-data-age` | `1095d` (3 years) | data older than this is treated as no data |
| `pacing.min-delay` | `1s` | minimum delay between outbound API calls (all sources share one gate) |
| `daily-cap.adzuna` | `200` | max Adzuna calls per rolling 24h |
| `daily-cap.h1bapi` | `100` | max h1bapi.com calls per rolling 24h |

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
