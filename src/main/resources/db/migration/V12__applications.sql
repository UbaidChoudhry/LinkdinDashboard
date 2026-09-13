-- "Apply with Claude" storage: the applicant profile used to fill non-resume form fields, one
-- row per apply batch (a run of the Claude-in-Chrome apply flow across a set of jobs), and one
-- row per job within that batch.
--
-- `applicant_profile` is a single-row table (id is pinned to 1 by the check constraint) holding
-- the free-text answers the resume itself can't supply - see apply/ApplyOrchestrator and the
-- Resumes tab's profile form.
--
-- `apply_batch` tracks one call to POST /api/applications: which resume and submit-toggle it
-- used, and running counters the UI polls while the batch is in flight.
--
-- `job_application` is the per-job outcome within a batch. `job_application_job` speeds up
-- "latest row per job_id" lookups (mirrors ai_match's per-resume lookup), ordered by id desc so
-- the newest attempt for a job comes first.
create table applicant_profile (
  id integer primary key check (id = 1),          -- single row
  full_name text not null, email text not null, phone text not null default '',
  location text not null default '', linkedin_url text not null default '',
  portfolio_url text not null default '', work_authorization text not null default '',
  requires_sponsorship integer not null default 0, salary_expectation text not null default '',
  extra_answers text not null default '',         -- free text: "how to answer anything else"
  updated_at text not null);

create table apply_batch (
  id integer primary key autoincrement, resume_id integer not null, submit integer not null,
  status text not null,                            -- running | ok | cancelled | failed
  total integer not null, done integer not null default 0,
  submitted integer not null default 0, needs_review integer not null default 0,
  failed integer not null default 0, skipped integer not null default 0,
  cost_usd real not null default 0, started_at text not null, finished_at text);

create table job_application (
  id integer primary key autoincrement, batch_id integer not null references apply_batch(id),
  job_id integer not null references job_listing(job_id) on delete cascade,
  status text not null,                            -- queued | filling | submitted | needs_review | failed | skipped
  notes text not null default '',                  -- model's summary / unanswered questions / error
  cost_usd real not null default 0, started_at text, finished_at text);

create index job_application_job on job_application (job_id, id desc);
