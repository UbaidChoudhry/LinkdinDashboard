package com.ubaid.jobdash.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code apply.company-links}: the step that finds a recommended LinkedIn job's posting on
 * the employer's own careers site with web search ({@link CompanyLinkFinder}). {@code batchSize}
 * jobs share one CLI call (the ~25k-token fixed overhead per call, HANDOFF.md §9, is the cost that
 * batching saves) and {@code concurrency} calls run at once. {@code minConfidence} is the lowest
 * match that becomes the job's apply link; weaker ones are shown but never applied to.
 * {@code maxTurns}, {@code maxBudgetUsd} and {@code timeout} bound one call, not the run - every
 * candidate is searched.
 */
@ConfigurationProperties(prefix = "apply.company-links")
public record CompanyLinkProperties(
        boolean enabled,
        String model,
        int batchSize,
        int concurrency,
        int minConfidence,
        int maxTurns,
        double maxBudgetUsd,
        Duration timeout
) {
}
