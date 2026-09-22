-- One row per LinkedIn job the company-link finder has looked at (apply/CompanyLinkFinder): the
-- posting it found on the employer's own careers site or ATS, how confident it is that it is the
-- same job, and why. An empty url means it looked and found nothing. job_listing.apply_url is
-- written only for a match at or above apply.company-links.min-confidence, so Apply with Claude
-- never acts on a weak one; this table keeps every candidate so the Results tab can show it.
-- A job with a row here is not searched again. No foreign key is relied on (SQLite enforcement is
-- off in this app, see ResumeService): an orphaned row is only ever looked up by a live job id,
-- and job ids are autoincrement, never reused - the same reasoning as ai_match.
create table company_link_match (
  job_id      integer primary key,
  url         text    not null default '',
  confidence  integer not null,
  matched_on  text    not null default '',   -- comma-separated: title,location,description,requisition,date
  note        text    not null default '',
  checked_at  text    not null);
