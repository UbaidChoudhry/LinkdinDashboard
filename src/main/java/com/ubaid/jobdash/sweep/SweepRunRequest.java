package com.ubaid.jobdash.sweep;

import java.util.List;

/**
 * Parameters for one sweep run, as accepted by {@link SweepService#startRun}.
 *
 * @param keywords        plain (non-boolean) search keywords.
 * @param location        the single search location to use when {@code shardingEnabled} is
 *                         false. Ignored (may be blank) when sharding is enabled.
 * @param hours            recency window in hours, converted to LinkedIn's {@code f_TPR}.
 * @param testMode        when true, the pagination loop is capped at
 *                         {@code sweep.budget.test-mode-page-cap} pages total across the run.
 * @param shardingEnabled opt-in flag: when true, the pagination loop runs once per shard in
 *                         {@code shards} (or the configured/default shard list if {@code shards}
 *                         is null or empty) instead of once against {@code location}. Sharding
 *                         is never automatic.
 * @param shards          an explicit shard list overriding the configured default, only
 *                         consulted when {@code shardingEnabled} is true.
 */
public record SweepRunRequest(
        String keywords,
        String location,
        int hours,
        boolean testMode,
        boolean shardingEnabled,
        List<String> shards,
        Integer pageCap
) {}
