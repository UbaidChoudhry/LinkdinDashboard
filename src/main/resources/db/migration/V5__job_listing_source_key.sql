-- Rebuild job_listing so its primary key is a source-agnostic surrogate instead of LinkedIn's
-- numeric job id. We're adding ATS job sources (Lever UUIDs like
-- 'ac978161-6f46-4f6b-ad9e-a258e642751c', Workday strings like 'JR2015623') whose ids are not
-- integers, so `job_id integer primary key` can no longer hold every source's native id.
--
-- The fix: `job_id` becomes an autoincrement surrogate key (unchanged in meaning to every reader
-- outside this table - the REST API and frontend keep addressing rows by this same integer, and
-- bookmarked /api/jobs/{id} URLs keep working), and the source's own id moves to a new
-- `source_job_id` column. The real natural key is now `(source, source_job_id)`.
--
-- SQLite cannot ALTER a primary key, so this is a create/copy/drop/rename rebuild. Every
-- existing row today is LinkedIn, and its numeric job_id is preserved unchanged across the
-- rebuild - we copy source_job_id = cast(job_id as text) and keep the same job_id value, so no
-- existing /api/jobs/{id} link or foreign key (sweep_run.id via last_seen_run_id is the other
-- direction; nothing references job_listing.job_id from elsewhere) breaks.
--
-- Both indexes V1 defined on this table are recreated byte-identically in their predicates:
-- job_detail_queue's partial predicate depends on the lowercase 'pass' literal (see HANDOFF.md
-- §3 - filter_verdict is written lowercase and the predicate has no collate). No separate index
-- is added for the new natural key: SQLite auto-creates an index backing a `unique` table
-- constraint (visible in sqlite_master as an autoindex), so `unique (source, source_job_id)`
-- below already provides it.

create table job_listing_new (
  job_id            integer primary key autoincrement,
  source            text    not null default 'linkedin',
  source_job_id     text    not null,
  title             text    not null,
  company           text    not null,
  location          text,
  posted_at         text,
  first_seen_at     text    not null,
  last_seen_at      text    not null,
  last_seen_run_id  integer not null,
  job_url           text    not null,
  company_url       text,
  filter_verdict    text,
  filter_version    integer,
  reject_reason     text,
  user_status       text,
  user_status_at    text,
  detail_fetched_at text,
  detail_status     text,
  apply_url         text,
  apply_domain      text,
  description       text,
  description_hash  text,
  salary_min        real,
  salary_max        real,
  salary_source     text,
  salary_source_detail text,
  suppressed        integer not null default 0,
  unique (source, source_job_id)
);

insert into job_listing_new
    (job_id, source, source_job_id, title, company, location, posted_at,
     first_seen_at, last_seen_at, last_seen_run_id, job_url, company_url,
     filter_verdict, filter_version, reject_reason, user_status, user_status_at,
     detail_fetched_at, detail_status, apply_url, apply_domain,
     description, description_hash, salary_min, salary_max, salary_source,
     salary_source_detail, suppressed)
select
    job_id, source, cast(job_id as text), title, company, location, posted_at,
    first_seen_at, last_seen_at, last_seen_run_id, job_url, company_url,
    filter_verdict, filter_version, reject_reason, user_status, user_status_at,
    detail_fetched_at, detail_status, apply_url, apply_domain,
    description, description_hash, salary_min, salary_max, salary_source,
    salary_source_detail, suppressed
from job_listing;

drop index job_detail_queue;
drop index job_browse;
drop table job_listing;
alter table job_listing_new rename to job_listing;

create index job_detail_queue on job_listing (posted_at desc)
  where detail_fetched_at is null and filter_verdict = 'pass';
create index job_browse on job_listing (last_seen_run_id, filter_verdict, user_status);
