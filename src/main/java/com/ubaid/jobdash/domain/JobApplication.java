package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code job_application} table: one job's outcome within an {@link ApplyBatch}.
 * Mirrors the schema in {@code src/main/resources/db/migration/V12__applications.sql} 1:1;
 * {@code startedAt}/{@code finishedAt} are represented as {@link Instant} (nullable - a queued
 * row has neither yet) and converted to/from ISO-8601 text via {@link Timestamps}. {@code status}
 * is one of {@code queued | filling | submitted | needs_review | failed | skipped}, stored
 * lowercase (HANDOFF §3).
 */
public record JobApplication(
        long id,
        long batchId,
        long jobId,
        String status,
        String notes,
        double costUsd,
        Instant startedAt,
        Instant finishedAt
) {
}
