package com.ubaid.jobdash.store;

import java.time.Instant;

/**
 * The fields a parsed job card contributes to a {@code job_listing} upsert, from any source
 * (LinkedIn, or an ATS board like Lever/Workday).
 *
 * <p>{@code source} identifies where the card came from (e.g. {@code "linkedin"},
 * {@code "lever"}, {@code "workday"}); {@code sourceJobId} is that source's own id for the
 * posting - LinkedIn's is numeric, Lever's is a UUID, Workday's is a string like
 * {@code "JR2015623"}. Together they form the natural key the upsert conflicts on; the table's
 * own {@code job_id} is an internal surrogate key assigned on insert.
 *
 * <p>{@code description} is the inline job description, when the source's search/list endpoint
 * returns one. LinkedIn's guest search cards carry none, so it is always {@code null} there.
 *
 * <p>This is intentionally distinct from any card type the parser task (running in parallel)
 * defines under {@code com.ubaid.jobdash.source.linkedin} — this package must not depend on
 * that one. Callers map their own parsed representation into this record before calling
 * {@link JobListingRepository#upsertAll}.
 */
public record JobCardInsert(
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
