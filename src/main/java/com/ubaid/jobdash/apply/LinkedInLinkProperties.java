package com.ubaid.jobdash.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code apply.linkedin-links:} block: the run phase that reads each recommended
 * LinkedIn posting's real external apply link out of a Chrome that is signed in to a burner
 * LinkedIn account (see {@link LinkedInApplyLinkResolver}). {@code batchSize} postings share
 * one CLI call - the ~25k-token fixed overhead per invocation (HANDOFF.md §9) is the cost that
 * matters - and {@code maxPerRun} bounds a run's spend outright.
 */
@ConfigurationProperties(prefix = "apply.linkedin-links")
public record LinkedInLinkProperties(
        boolean enabled,
        int batchSize,
        int maxPerRun,
        int maxTurns,
        double maxBudgetUsd,
        Duration timeout
) {
}
