package com.ubaid.jobdash.sweep;

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
        String sources
) {
}
