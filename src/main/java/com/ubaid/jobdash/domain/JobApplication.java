package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code job_application} table: one job's outcome within an {@link ApplyBatch}.
 * Mirrors the schema in {@code src/main/resources/db/migration/V12__applications.sql} and
 * {@code V13__application_activity.sql} 1:1; {@code startedAt}/{@code finishedAt} are represented
 * as {@link Instant} (nullable - a queued row has neither yet) and converted to/from ISO-8601
 * text via {@link Timestamps}. {@code status} is one of
 * {@code queued | filling | submitted | needs_review | failed | skipped}, stored lowercase
 * (HANDOFF §3). {@code sessionId} is the CLI's {@code --session-id} (null for a LinkedIn row,
 * which never calls the CLI); {@code lastActivity} is the latest live event's text (empty until
 * one arrives); {@code logPath} is the per-job transcript file under {@code logs/apply/} (null
 * for a LinkedIn row).
 */
public record JobApplication(
        long id,
        long batchId,
        long jobId,
        String status,
        String notes,
        double costUsd,
        Instant startedAt,
        Instant finishedAt,
        String sessionId,
        String lastActivity,
        String logPath
) {
}
