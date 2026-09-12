-- Repairs LinkedIn rows mis-stamped by mixed-source runs before location classification was
-- scoped to non-LinkedIn sources (see JobListingRepository#findDistinctLocationsByRun /
-- #applyLocationVerdict). A LinkedIn row that picked up a confidently-non-US verdict from that
-- bug stays hidden forever by the `coalesce(location_us,1)` read-path rule even after the fix,
-- so clear the stamp here: LinkedIn results are already location-scoped by the search query
-- itself and were never meant to carry a location verdict.

update job_listing set location_us = null, location_confident = null where source = 'linkedin';
