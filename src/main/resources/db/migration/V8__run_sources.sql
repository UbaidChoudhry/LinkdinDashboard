-- Lets one sweep_run cover LinkedIn and/or ATS sources, and optionally an AI resume scan.
--
-- `sources` is a comma-separated list (e.g. 'linkedin', or 'greenhouse,lever,workday') - plain
-- text rather than a join table, mirroring `shards_used`'s existing convention on this same
-- table. Defaulting to 'linkedin' means every row created before this migration (and any caller
-- that still omits the field) keeps today's meaning unchanged.
--
-- `resume_id` is nullable: not every run asks for an AI scan, and a run started with no resume
-- uploaded at all must remain possible (see RunOrchestrator - a missing resume skips the scan
-- silently rather than failing the run). Deliberately no foreign key: a resume can be deleted
-- after a run references it, and that must not break reading old runs.
--
-- `companies_done` / `companies_total` are the ATS-run equivalent of `pages_fetched` for a
-- LinkedIn run - both default to 0 so a LinkedIn-only run (which never touches these columns)
-- reads back as 0/0, matching SweepProgress's documented default for that case.
--
-- Plain `alter table add column` throughout - SQLite supports this natively, no rebuild needed
-- (unlike V5, which had to rebuild the table because it changed a primary key).

alter table sweep_run add column sources text not null default 'linkedin';
alter table sweep_run add column resume_id integer;
alter table sweep_run add column companies_done integer not null default 0;
alter table sweep_run add column companies_total integer not null default 0;
