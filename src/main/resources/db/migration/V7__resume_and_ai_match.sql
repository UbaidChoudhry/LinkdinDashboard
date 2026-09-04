-- Resume storage and the AI job-match results computed against it.
--
-- `resume` holds one row per uploaded resume: the original file is kept on disk under
-- data/resumes/ (see resume/ResumeService), while `content_text` holds the extracted plain
-- text that is actually sent to Claude for comparison against a job description. At most one
-- resume is ever the default (enforced in store/ResumeRepository#setDefault, not in SQL).
--
-- `ai_match` is written by the later task that compares a resume's text against a job
-- description via the Claude CLI. Created here so this migration owns the full feature's
-- schema; otherwise untouched by this task.
create table resume (
  id                integer primary key autoincrement,
  name              text    not null,
  original_filename text    not null,
  content_type      text    not null,   -- 'application/pdf' | 'text/plain' | 'text/markdown'
  stored_path       text    not null,   -- path of the kept original, relative to the repo root
  content_text      text    not null,   -- extracted plain text sent to Claude
  char_count        integer not null,
  is_default        integer not null default 0,
  uploaded_at       text    not null    -- ISO-8601 text; see domain/Timestamps.java
);

create table ai_match (
  job_id       integer not null,
  resume_id    integer not null references resume(id) on delete cascade,
  recommended  integer not null,        -- 0/1
  reason       text    not null,
  model        text    not null,
  run_id       integer,
  scanned_at   text    not null,
  primary key (job_id, resume_id)
);
create index ai_match_resume_recommended on ai_match (resume_id, recommended);
