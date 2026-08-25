package com.ubaid.jobdash.store;

import java.time.Instant;

/**
 * The fields a parsed LinkedIn job card contributes to a {@code job_listing} upsert.
 *
 * <p>This is intentionally distinct from any card type the parser task (running in parallel)
 * defines under {@code com.ubaid.jobdash.source.linkedin} — this package must not depend on
 * that one. Callers map their own parsed representation into this record before calling
 * {@link JobListingRepository#upsertAll}.
 */
public record JobCardInsert(
        long jobId,
        String title,
        String company,
        String location,
        Instant postedAt,
        String jobUrl,
        String companyUrl
) {
}
