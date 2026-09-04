-- The ATS company registry: a catalog/selection split.
--
-- `ats_company` holds two very different populations under one roof:
--   1. Companies the user has actually vetted and turned on (enabled=1) - a run only ever
--      visits these.
--   2. Everything imported in bulk from a public slug catalog (>15,000 rows, growing) - a raw
--      list of "this company probably has a Greenhouse/Lever/Workday board", unverified.
--
-- Keeping both in one table (rather than a separate "candidates" table the user promotes from)
-- means enabling a company is a single UPDATE and the catalog's dead-slug bookkeeping
-- (consecutive_failures/status) applies uniformly whether the row came from a bulk import or was
-- typed in by hand.
--
-- Imported rows are disabled by default (enabled=0, status='unverified') because the catalog is
-- unverified and un-vetted: importing it must never silently start hammering 15,000 companies'
-- job boards, and must never flip a switch the user set themselves (see
-- SlugCatalogImportService's insertOrIgnore semantics - importing is additive-only).

create table ats_company (
  id                    integer primary key autoincrement,
  ats                   text    not null, -- 'greenhouse' | 'lever' | 'workday'
  slug                  text    not null, -- board token; for Workday, the tenant (first label of the host)
  company               text    not null, -- display name
  host                  text,             -- Workday only, e.g. 'nvidia.wd5.myworkdayjobs.com'
  site                  text,             -- Workday only, the site id discovered from robots.txt
  enabled               integer not null default 0,
  status                text    not null default 'unverified', -- 'unverified' | 'active' | 'dead'
  consecutive_failures  integer not null default 0,
  last_checked_at       text,
  last_ok_at            text,
  last_job_count        integer,
  added_at              text    not null,
  unique (ats, slug)
);

-- Backs the run's lookup: enabled, non-dead companies for a given set of ATS names.
create index ats_company_run on ats_company (enabled, status, ats);

-- Seed: 42 companies verified live against the real APIs on 2026-09-07. Do not add, remove, or
-- guess at others here - see HANDOFF.md for the verification discipline this repo follows.

-- Greenhouse (host/site are NULL for this ATS)
insert into ats_company (ats, slug, company, host, site, enabled, status, added_at) values
  ('greenhouse', 'airbnb',     'Airbnb',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'stripe',     'Stripe',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'databricks', 'Databricks', null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'coinbase',   'Coinbase',   null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'robinhood',  'Robinhood',  null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'anthropic',  'Anthropic',  null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'figma',      'Figma',      null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'discord',    'Discord',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'reddit',     'Reddit',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'twilio',     'Twilio',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'cloudflare', 'Cloudflare', null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'datadog',    'Datadog',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'gitlab',     'GitLab',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'asana',      'Asana',      null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'instacart',  'Instacart',  null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'flexport',   'Flexport',   null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'lyft',       'Lyft',       null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'pinterest',  'Pinterest',  null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'sofi',       'SoFi',       null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'affirm',     'Affirm',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'brex',       'Brex',       null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'vercel',     'Vercel',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'samsara',    'Samsara',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'scaleai',    'Scale AI',   null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'mongodb',    'MongoDB',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('greenhouse', 'elastic',    'Elastic',    null, null, 1, 'active', '2026-09-07T00:00:00Z');

-- Lever (host/site are NULL for this ATS)
insert into ats_company (ats, slug, company, host, site, enabled, status, added_at) values
  ('lever', 'palantir',  'Palantir',   null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'spotify',   'Spotify',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'ro',        'Ro',         null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'gopuff',    'Gopuff',     null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'alloy',     'Alloy',      null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'matillion', 'Matillion',  null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'veeva',     'Veeva',      null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'rigetti',   'Rigetti',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'pattern',   'Pattern',    null, null, 1, 'active', '2026-09-07T00:00:00Z'),
  ('lever', 'shieldai',  'Shield AI',  null, null, 1, 'active', '2026-09-07T00:00:00Z');

-- Workday (slug is the tenant - the first label of host; site is the discovered board id)
insert into ats_company (ats, slug, company, host, site, enabled, status, added_at) values
  ('workday', 'nvidia',     'NVIDIA',     'nvidia.wd5.myworkdayjobs.com',     'NVIDIAExternalCareerSite', 1, 'active', '2026-09-07T00:00:00Z'),
  ('workday', 'salesforce', 'Salesforce', 'salesforce.wd12.myworkdayjobs.com','External_Career_Site',     1, 'active', '2026-09-07T00:00:00Z'),
  ('workday', 'workday',    'Workday',    'workday.wd5.myworkdayjobs.com',    'Workday',                  1, 'active', '2026-09-07T00:00:00Z'),
  ('workday', 'adobe',      'Adobe',      'adobe.wd5.myworkdayjobs.com',      'external_experienced',     1, 'active', '2026-09-07T00:00:00Z'),
  ('workday', 'paypal',     'PayPal',     'paypal.wd1.myworkdayjobs.com',     'jobs',                     1, 'active', '2026-09-07T00:00:00Z'),
  ('workday', 'visa',       'Visa',       'visa.wd5.myworkdayjobs.com',       'Visa',                     1, 'active', '2026-09-07T00:00:00Z');
