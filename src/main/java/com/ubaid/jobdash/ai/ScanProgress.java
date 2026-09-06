package com.ubaid.jobdash.ai;

import java.time.Instant;

/**
 * A live snapshot of an in-flight AI scan, published as each batch completes so the dashboard can
 * show movement during what is otherwise a long silent phase.
 *
 * <p>Counts here are reported from the batch that actually finished, not from the order batches
 * were submitted in — with {@code ai.concurrency} batches in flight at once, reporting in
 * submission order would make progress jump in lurches while a slow early batch held everything
 * behind it.
 *
 * @param batchesDone    batches that have returned, successfully or not.
 * @param batchesTotal   batches this scan will run in total.
 * @param jobsScanned    jobs that actually came back with a verdict.
 * @param jobsTotal      jobs this scan set out to score (already-cached ones are excluded).
 * @param failedBatches  batches that returned nothing usable (a timeout, or a CLI failure).
 * @param costUsd        cost reported by the CLI so far; billed to the Claude subscription.
 * @param startedAt      when the scan began, so the UI can render elapsed time itself.
 */
public record ScanProgress(
        int batchesDone,
        int batchesTotal,
        int jobsScanned,
        int jobsTotal,
        int recommended,
        int notRecommended,
        int failedBatches,
        double costUsd,
        Instant startedAt
) {

    /** The snapshot published before the first batch returns, so the UI shows totals immediately. */
    public static ScanProgress starting(int batchesTotal, int jobsTotal, Instant startedAt) {
        return new ScanProgress(0, batchesTotal, 0, jobsTotal, 0, 0, 0, 0.0, startedAt);
    }
}
