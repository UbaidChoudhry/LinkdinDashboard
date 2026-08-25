package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.sweep.SweepProgress;

import java.time.Instant;

/**
 * A run's status and counters, as returned by {@code GET /api/runs}, {@code GET /api/runs/{id}}
 * and streamed over {@code GET /api/runs/{id}/stream}. Combines the persisted {@link SweepRun}
 * row with the live {@link SweepProgress} snapshot when one is available in this process.
 */
public record RunResponse(
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
        String currentShard,
        int pagesFetched,
        int requestsMade,
        int cardsSeen,
        int jobsNew,
        boolean saturated
) {

    /** Builds a response from the persisted row alone (no live progress known to this process). */
    public static RunResponse fromRun(SweepRun run) {
        return new RunResponse(run.id(), run.startedAt(), run.finishedAt(), run.status(), run.keywords(),
                run.location(), run.hours(), run.testMode(), run.pageCap(), run.shardsUsed(), null,
                run.pagesFetched(), run.requestsMade(), run.cardsSeen(), run.jobsNew(), run.saturated());
    }

    /** Builds a response from the persisted row's static fields plus a live progress snapshot. */
    public static RunResponse fromRunAndProgress(SweepRun run, SweepProgress progress) {
        return new RunResponse(run.id(), run.startedAt(), run.finishedAt(), progress.status(), run.keywords(),
                run.location(), run.hours(), run.testMode(), run.pageCap(), run.shardsUsed(), progress.currentShard(),
                progress.pagesFetched(), progress.requestsMade(), progress.cardsSeen(), progress.jobsNew(),
                progress.saturated());
    }
}
