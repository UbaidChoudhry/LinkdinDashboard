-- Supports "LinkedIn postings -> apply on the company's own ATS" (plan navigation-you-snappy-cosmos).
-- `apply_url` / `apply_domain` have existed on `job_listing` since V1 for the Greenhouse/Lever/
-- Workday sources, but were only ever populated by those sources' own listing data. This is the
-- first feature to populate them for a `source='linkedin'` row: `apply/LinkedInAtsMatcher`
-- cross-references the company's own ATS board and, on a match, writes the same two columns so a
-- LinkedIn row can carry a direct apply link like any ATS row does.
--
-- `apply_kind` is informational only, not a matching input: `onsite` means the LinkedIn posting
-- itself was Easy Apply (parsed from the detail fragment, `source.linkedin.DetailParser`), and its
-- only consumer is the UI's "Easy Apply" tag on an unmatched row.
--
-- `apply_match_note` explains the matcher's decision either way - "description 0.80 (next 0.51)",
-- "req id R3215", "ambiguous: 3 candidates, best 0.52", "no title match" - shown as the UI's
-- apply-link tooltip and logged per run.
alter table job_listing add column apply_kind text;
alter table job_listing add column apply_match_note text;
