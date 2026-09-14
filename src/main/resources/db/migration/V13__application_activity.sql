-- Live observability + resumable sessions for "Apply with Claude" (HANDOFF.md §12): before this,
-- a job's CLI call reported nothing until it exited, so cancelling a stuck run discarded
-- everything Claude had done. `session_id` is the CLI's own --session-id (a UUID we generate),
-- persisted so a stuck/failed job can be resumed in a terminal with `claude --resume <id> --chrome`
-- to see exactly where the browser was left. `last_activity` is the latest live event's text, so
-- the UI can show progress while a job is still running. `log_path` points at the per-job
-- transcript file under logs/apply/ (gitignored) that records every event as it happens.
alter table job_application add column session_id text;
alter table job_application add column last_activity text not null default '';
alter table job_application add column log_path text;
