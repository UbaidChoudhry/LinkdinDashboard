package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code job_listing} table. Mirrors the schema in
 * {@code src/main/resources/db/migration/V1__init.sql} 1:1; all timestamp columns are
 * represented as {@link Instant} and converted to/from ISO-8601 text via {@link Timestamps}.
 */
public record JobListing(
        long jobId,
        String sourceJobId,
        String source,
        String title,
        String company,
        String location,
        Instant postedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long lastSeenRunId,
        String jobUrl,
        String companyUrl,
        FilterVerdict filterVerdict,
        Integer filterVersion,
        String rejectReason,
        UserStatus userStatus,
        Instant userStatusAt,
        Instant detailFetchedAt,
        String detailStatus,
        String applyUrl,
        String applyDomain,
        String description,
        String descriptionHash,
        Double salaryMin,
        Double salaryMax,
        String salarySource,
        String salarySourceDetail,
        boolean suppressed
) {
}
