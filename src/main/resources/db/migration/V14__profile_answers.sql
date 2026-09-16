-- Replaces the applicant profile's free-text "extra answers" catch-all with a proper table of
-- question/answer rows ("Apply with Claude" §12, follow-up work). Before this, a question Claude
-- could not answer just went into the job's `job_application.notes` and was lost the moment the
-- batch finished - the user had no durable place to see it or answer it, and no way to feed the
-- answer back into future batches. `profile_answer` fixes that: every question Claude could not
-- answer becomes a `pending` row here (via `apply.ProfileAnswerRepository.recordUnanswered`), the
-- user answers it once in the Resumes tab's new Questions & answers list, and every later batch's
-- prompt includes it as a `KNOWN ANSWERS` line so Claude never has to ask again. `question_key` is
-- the normalized form of `question` (`apply.QuestionKey.normalize`) and is unique so the same
-- question asked with slightly different punctuation/casing across postings collides onto one row
-- instead of duplicating. `asked_count`/`last_job_id`/`last_company` track how often and where a
-- still-unanswered question keeps coming up, purely for the user's own context.
--
-- `apply_batch` gains `new_questions` (how many *new* pending rows this batch's run created - the
-- UI badges this) and `report_path` (the end-of-run markdown report written by ApplyOrchestrator,
-- summarizing every job's outcome and every question the user still needs to answer).
--
-- The migration below carries forward anything the user had already typed into the old
-- `applicant_profile.extra_answers` free-text field, so upgrading never silently drops what they
-- wrote - it becomes one `answered` row under a fixed, recognizable question text so it keeps
-- showing up (and keeps being usable by the prompt) exactly as before.
create table profile_answer (
  id integer primary key autoincrement,
  question text not null,
  question_key text not null unique,
  answer text not null default '',
  status text not null,                 -- answered | pending
  asked_count integer not null default 0,
  last_job_id integer,
  last_company text not null default '',
  created_at text not null,
  updated_at text not null);

alter table apply_batch add column new_questions integer not null default 0;
alter table apply_batch add column report_path text;

insert into profile_answer (question, question_key, answer, status, asked_count, last_job_id, last_company,
                             created_at, updated_at)
select 'General notes (how to answer anything else)', 'general notes (how to answer anything else)',
       extra_answers, 'answered', 0, null, '', updated_at, updated_at
from applicant_profile
where trim(extra_answers) <> '';
