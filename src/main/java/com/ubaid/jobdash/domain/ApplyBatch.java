package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code apply_batch} table: one call to start "Apply with Claude" across a set of
 * jobs. Mirrors the schema in {@code src/main/resources/db/migration/V12__applications.sql} 1:1;
 * {@code startedAt}/{@code finishedAt} are represented as {@link Instant} and converted to/from
 * ISO-8601 text via {@link Timestamps}. {@code status} is one of {@code running | ok | cancelled
 * | failed}, stored lowercase (HANDOFF §3). {@code finishedAt} is null while running.
 */
public record ApplyBatch(
        long id,
        long resumeId,
        boolean submit,
        String status,
        int total,
        int done,
        int submitted,
        int needsReview,
        int failed,
        int skipped,
        double costUsd,
        Instant startedAt,
        Instant finishedAt
) {
}
