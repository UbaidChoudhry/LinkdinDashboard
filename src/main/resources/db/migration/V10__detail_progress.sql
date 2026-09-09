-- Progress counters for the detail-fetch phase a LinkedIn run performs after collection: how many
-- job-detail fragments it has fetched so far, out of how many it queued. The LinkedIn analogue of
-- `companies_done` / `companies_total` for an ATS run, defaulting to 0/0 so every existing row and
-- every ATS run (which never touches these) reads back unchanged.
--
-- The rows themselves are stamped on job_listing (`detail_fetched_at`, `detail_status`,
-- `description`, `description_hash`), all of which have existed since V1 - this migration adds
-- nothing there. Plain `alter table add column`; SQLite needs no rebuild for that.

alter table sweep_run add column details_done integer not null default 0;
alter table sweep_run add column details_total integer not null default 0;
