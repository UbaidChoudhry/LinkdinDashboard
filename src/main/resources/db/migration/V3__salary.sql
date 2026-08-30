-- Salary enrichment foundation: the (company,title) estimate cache, pre-aggregated local
-- DOL LCA disclosure data, and the external-API request log that backs the salary rate
-- limiter's rolling daily cap. All timestamps/dates are ISO-8601 text (SQLite has no
-- datetime type); see com.ubaid.jobdash.domain.Timestamps.

create table salary_estimate (
  company_key  text not null,
  title_key    text not null,
  salary_min   real,
  salary_max   real,
  currency     text default 'USD',
  source       text not null,          -- 'lca' | 'adzuna' | 'h1bapi' | 'none'
  data_date    text,                   -- date the underlying data point is from (3-year staleness rule)
  sample_count integer,
  fetched_at   text not null,          -- when we recorded this row (90-day TTL)
  primary key (company_key, title_key)
);

create table lca_wage (
  employer_key     text    not null,
  soc_code         text    not null,
  state            text    not null default '',   -- '' = national / any-state aggregate
  wage_p25         real,
  wage_p50         real,
  wage_p75         real,
  wage_max         real,
  sample_count     integer not null,
  latest_data_date text    not null,
  primary key (employer_key, soc_code, state)
);
create index lca_wage_lookup on lca_wage (employer_key, soc_code);

create table external_request_log (
  id           integer primary key autoincrement,
  source       text    not null,       -- 'adzuna' | 'h1bapi'
  requested_at text    not null,
  url          text    not null,
  status_code  integer
);
create index external_request_log_time on external_request_log (source, requested_at desc);
