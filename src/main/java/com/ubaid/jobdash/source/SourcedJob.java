package com.ubaid.jobdash.source;

import java.time.Instant;

/**
 * A job posting normalized across all ATS sources (Greenhouse, Lever, Workday). Each
 * {@link JobSource} implementation maps its provider's payload onto this shape so the rest of
 * the app never needs to know which board a job came from.
 * <p>
 * {@code postedAt} is nullable: Workday's search page carries only a prose "Posted 30+ Days
 * Ago" string, and the real date lives behind a per-job detail request. Jobs beyond
 * {@code ats.workday.max-details-per-run} are returned with a null {@code postedAt} (and null
 * {@code description}) rather than being dropped.
 */
public record SourcedJob(
        String source,
        String sourceJobId,
        String title,
        String company,
        String location,
        Instant postedAt,
        String jobUrl,
        String companyUrl,
        String description
) {
}
