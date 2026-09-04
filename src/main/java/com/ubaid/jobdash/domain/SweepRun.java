package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * A row of the {@code sweep_run} table. Mirrors the schema in
 * {@code src/main/resources/db/migration/V1__init.sql}, plus the source/resume/company columns
 * added by {@code V8__run_sources.sql}.
 *
 * @param sources        comma-separated source list, e.g. {@code "linkedin"} or
 *                        {@code "greenhouse,lever"}. Defaults to {@code "linkedin"}.
 * @param resumeId       the resume an AI scan ran against, if any; null when no scan was requested.
 * @param companiesDone  ATS runs only: companies visited so far. Always 0 for a LinkedIn run.
 * @param companiesTotal ATS runs only: companies selected for this run. Always 0 for a LinkedIn run.
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
        boolean saturated,
        String sources,
        Long resumeId,
        int companiesDone,
        int companiesTotal
) {
}
