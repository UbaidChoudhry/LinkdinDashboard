create table job_listing (
  job_id            integer primary key,
  source            text    not null default 'linkedin',
  title             text    not null,
  company           text    not null,
  location          text,
  posted_at         text,
  first_seen_at     text    not null,
  last_seen_at      text    not null,
  last_seen_run_id  integer not null,
  job_url           text    not null,
  company_url       text,
  filter_verdict    text,
  filter_version    integer,
  reject_reason     text,
  user_status       text,
  user_status_at    text,
  detail_fetched_at text,
  detail_status     text,
  apply_url         text,
  apply_domain      text,
  description       text,
  description_hash  text,
  salary_min        real,
  salary_max        real,
  salary_source     text,
  suppressed        integer not null default 0
);
create index job_detail_queue on job_listing (posted_at desc)
  where detail_fetched_at is null and filter_verdict = 'pass';
create index job_browse on job_listing (last_seen_run_id, filter_verdict, user_status);

create table sweep_run (
  id            integer primary key autoincrement,
  started_at    text not null,
  finished_at   text,
  status        text not null,
  keywords      text not null,
  location      text not null,
  hours         integer not null default 24,
  test_mode     integer not null default 0,
  page_cap      integer,
  shards_used   text,
  pages_fetched integer not null default 0,
  requests_made integer not null default 0,
  cards_seen    integer not null default 0,
  jobs_new      integer not null default 0,
  saturated     integer not null default 0
);

create table exclude_word (
  word     text primary key collate nocase,
  added_at text not null
);

create table company_blocklist (
  company  text primary key collate nocase,
  reason   text not null,
  evidence text,
  added_at text not null
);

create table filter_state (
  id             integer primary key check (id = 1),
  filter_version integer not null
);

create table request_log (
  id           integer primary key autoincrement,
  run_id       integer references sweep_run(id),
  requested_at text    not null,
  url          text    not null,
  status_code  integer,
  outcome      text    not null,
  bytes        integer,
  waited_ms    integer
);
create index request_log_time on request_log (requested_at desc);

create table circuit_state (
  id                integer primary key check (id = 1),
  state             text    not null default 'closed',
  opened_at         text,
  reopen_after      text,
  consecutive_trips integer not null default 0,
  soft_failures     integer not null default 0,
  last_reason       text
);
