package com.ubaid.jobdash.web.dto;

import java.util.List;

/**
 * Body of {@code POST /api/runs}.
 *
 * @param keywords   plain search keywords; required.
 * @param hours      recency window in hours; defaults to 24 when null.
 * @param location   single search location, used when {@code useShards} is false.
 * @param testMode   caps the run at the configured test-mode page budget; null/absent means false.
 * @param pageCap    explicit per-run page cap. Takes precedence over the configured test-mode
 *                   default; applies whether or not {@code testMode} is on. Null means uncapped
 *                   (or the test-mode default when {@code testMode} is true).
 * @param useShards  when true, runs the configured/default shard list instead of one location;
 *                   null/absent means false.
 * @param shards     optional explicit shard list overriding the configured default; only
 *                   consulted when {@code useShards} is true.
 * @param sources    which source(s) to run: any of {@code "linkedin"}, {@code "greenhouse"},
 *                   {@code "lever"}, {@code "workday"}. Null/empty means {@code ["linkedin"]},
 *                   preserving today's behaviour for any existing caller. Any combination is
 *                   allowed; a mixed run collects LinkedIn and the boards back to back, each
 *                   under its own request budget.
 * @param usOnly     restricts ATS results to postings identifiably in the United States.
 *                   <b>Defaults to true</b> when absent - the boards are worldwide, so the
 *                   useful default for this tool is US-only; pass false explicitly to widen it.
 * @param resumeId   an explicit resume to scan collected jobs against; null falls back to the
 *                   default resume, or skips the AI scan entirely if none exists. Ignored for a
 *                   LinkedIn-only run (LinkedIn's guest search carries no job description).
 */
public record CreateRunRequest(
        String keywords,
        Integer hours,
        String location,
        Boolean testMode,
        Integer pageCap,
        Boolean useShards,
        List<String> shards,
        List<String> sources,
        Long resumeId,
        Boolean usOnly
) {

    public boolean testModeOrDefault() {
        return Boolean.TRUE.equals(testMode);
    }

    public boolean useShardsOrDefault() {
        return Boolean.TRUE.equals(useShards);
    }

    /** {@code sources}, or {@code ["linkedin"]} when null/empty. */
    public List<String> sourcesOrDefault() {
        return sources == null || sources.isEmpty() ? List.of("linkedin") : sources;
    }

    /** Absent means true: US-only is the default, and must be opted OUT of explicitly. */
    public boolean usOnlyOrDefault() {
        return !Boolean.FALSE.equals(usOnly);
    }
}
