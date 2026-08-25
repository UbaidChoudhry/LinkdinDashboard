package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code sweep_run} table. Mirrors the schema in
 * {@code src/main/resources/db/migration/V1__init.sql} 1:1.
 */
public record SweepRun(
        long id,
        Instant startedAt,
        Instant finishedAt,
        String status,
        String keywords,
        String location,
        int hours,
        boolean testMode,
        Integer pageCap,
        String shardsUsed,
        int pagesFetched,
        int requestsMade,
        int cardsSeen,
        int jobsNew,
        boolean saturated
) {
}
