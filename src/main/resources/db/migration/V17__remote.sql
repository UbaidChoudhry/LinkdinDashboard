-- Whether a job can be done fully remotely, as judged from its description (see
-- source/location/RemoteClassifier and HANDOFF.md §15).
--
-- LinkedIn's guest endpoint gives no way to know: its cards carry a city, never "Remote", and the
-- remote facet (f_WT=2) is ignored (HANDOFF.md §2). The posting's own prose is the only source,
-- so the answer is read out of the description.
--
-- `remote` is TRI-STATE, like location_us:
--   NULL = not decided yet (no description yet, or the CLI could not answer) - retried next time
--   1    = remote
--   0    = not remote (on-site, hybrid, or the posting never says it is remote)
-- `remote_note` is the few words of the posting that decided it, shown as the cell's tooltip.

alter table job_listing add column remote integer;
alter table job_listing add column remote_note text;
