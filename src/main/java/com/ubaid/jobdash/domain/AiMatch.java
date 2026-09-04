package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code ai_match} table: one resume's cached verdict on one job. Mirrors the
 * schema in {@code src/main/resources/db/migration/V7__resume_and_ai_match.sql} 1:1;
 * {@code scannedAt} is represented as an {@link Instant} and converted to/from ISO-8601 text
 * via {@link Timestamps}. {@code runId} is nullable - a manual re-scan by job id has no run.
 */
public record AiMatch(
        long jobId,
        long resumeId,
        boolean recommended,
        String reason,
        String model,
        Long runId,
        Instant scannedAt
) {
}
