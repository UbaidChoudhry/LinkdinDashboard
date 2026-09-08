-- Replaces hand-written US/non-US location matching with a judgement made by Claude.
--
-- Job boards write locations as free-form text in wildly inconsistent shapes - 'US - Austin, TX',
-- 'Israel, Yokneam' (country FIRST), 'USA.VA.Reston', 'California - Remote', '11 Locations'.
-- A pattern matcher over country/state/city lists could not keep up with that, and got cases like
-- 'Remote - CA' (California or Canada?) confidently wrong. See HANDOFF.md §9.
--
-- `job_listing.location_us` is deliberately TRI-STATE:
--   NULL = not classified yet, 1 = United States, 0 = not the United States.
-- NULL matters. Classification runs once per run and can legitimately not have happened yet (the
-- claude CLI is missing, timed out, or the row predates this feature). A row in that state stays
-- VISIBLE and is retried on the next run, rather than being hidden on a guess.
--
-- `job_listing.location_confident` carries Claude's own confidence in that call. Strings like
-- Workday's '11 Locations' placeholder, or a bare 'San Jose' (California or Costa Rica), name no
-- country at all. Those are kept and flagged in the UI rather than dropped, so the reading rule
-- everywhere is "hide only a CONFIDENTLY non-US row":
--     not (location_us = 0 and location_confident = 1)
--
-- These are NOT stored in `filter_verdict`, even though that column is already the chokepoint that
-- hides rows from the search tab, the AI scan and salary enrichment. FilterEngine.reevaluateStale()
-- rewrites filter_verdict wholesale from title/company rules alone, so a location decision parked
-- there would be silently reverted the next time an exclude word changed.
--
-- `location_verdict` caches one decision per distinct normalised location string. Locations repeat
-- heavily (99 stored jobs spanned only 34 distinct strings), so this cache is what keeps the
-- feature to roughly one CLI call per run instead of one per job.
--
-- There is deliberately NO TTL on that cache, unlike salary_estimate: which country a location
-- string refers to does not change. Do not add one.
--
-- Plain `alter table add column` - SQLite supports this natively, no table rebuild needed.

alter table job_listing add column location_us integer;
alter table job_listing add column location_confident integer;

create table location_verdict (
  location_key    text    not null primary key,  -- normalised; see source/location/LocationKey
  location_sample text    not null,              -- one raw string that produced this key, for debugging
  in_us           integer not null,              -- 0/1
  confident       integer not null,              -- 0/1, Claude's own confidence in the call
  model           text    not null,
  decided_at      text    not null               -- ISO-8601 text; see com.ubaid.jobdash.domain.Timestamps
);
