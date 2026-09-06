package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.ai.ScanProgress;

/**
 * An in-memory progress snapshot for one sweep run, as tracked by {@link RunProgressRegistry}.
 * Mirrors (a superset of) the counters persisted onto {@code sweep_run} via
 * {@code SweepRunRepository}. Surfaced over SSE by {@code RunController}.
 *
 * @param status         {@code "running"} while the run is in flight; a terminal value
 *                        ({@code "ok"}, {@code "capped"}, {@code "budget_exhausted"},
 *                        {@code "blocked"}, {@code "failed"}, {@code "cancelled"} or
 *                        {@code "no_sources"}) once done, or the transient {@code "scanning"}
 *                        state between an ATS run's collection phase and its AI resume scan.
 * @param scan          live AI-scan progress while {@code status} is {@code "scanning"}; null
 *                       for a LinkedIn run and before the scan phase begins.
 * @param currentShard   the shard currently being paginated, or null when the run isn't
 *                        sharding (or is between shards / finished / not a LinkedIn run).
 * @param companiesDone  ATS runs only: companies visited so far. Always 0 for a LinkedIn run.
 * @param companiesTotal ATS runs only: companies selected for this run. Always 0 for a LinkedIn run.
 * @param sources        comma-separated source list for this run, e.g. {@code "linkedin"} or
 *                        {@code "greenhouse,lever"}.
 */
public record SweepProgress(
        long runId,
        String status,
        String currentShard,
        int pagesFetched,
        int requestsMade,
        int cardsSeen,
        int jobsNew,
        boolean saturated,
        int companiesDone,
        int companiesTotal,
        String sources,
        ScanProgress scan
) {

    /** Copy of this snapshot with the AI-scan progress replaced. */
    public SweepProgress withScan(ScanProgress newScan) {
        return new SweepProgress(runId, status, currentShard, pagesFetched, requestsMade, cardsSeen, jobsNew,
                saturated, companiesDone, companiesTotal, sources, newScan);
    }
}
