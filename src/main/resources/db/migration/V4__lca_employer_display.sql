-- Carries the human-readable matched entity through the salary pipeline. LCA employer names are
-- legal entities that CompanyKey normalizes; once prefix/alias matching lets "amazon" match
-- "amazon com services", the UI needs to show which legal entity was actually matched rather
-- than the normalized key. All columns nullable — existing rows predate the match detail.

alter table lca_wage add column employer_display text;      -- raw EMPLOYER_NAME of the group, e.g. 'AMAZON.COM SERVICES LLC'
alter table salary_estimate add column source_detail text;  -- matched entity behind the cached estimate
alter table job_listing add column salary_source_detail text;
