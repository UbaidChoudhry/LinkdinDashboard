package com.ubaid.jobdash.sweep;

/**
 * An in-memory progress snapshot for one sweep run, as tracked by {@link SweepService}'s
 * progress registry. Mirrors (a superset of) the counters persisted onto {@code sweep_run} via
 * {@code SweepRunRepository#updateProgress}. Task 7 surfaces this over SSE; this task only
 * maintains the registry and exposes {@link SweepService#progress(long)} to read it.
 *
 * @param status        {@code "running"} while the run is in flight; a terminal value
 *                       ({@code "ok"}, {@code "capped"}, {@code "budget_exhausted"},
 *                       {@code "blocked"}, {@code "failed"}, or {@code "cancelled"}) once done.
 * @param currentShard  the shard currently being paginated, or null when the run isn't
 *                       sharding (or is between shards / finished).
 */
public record SweepProgress(
        long runId,
        String status,
        String currentShard,
        int pagesFetched,
        int requestsMade,
        int cardsSeen,
        int jobsNew,
        boolean saturated
) {
}
