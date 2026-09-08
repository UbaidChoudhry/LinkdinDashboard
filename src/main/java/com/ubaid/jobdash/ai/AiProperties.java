package com.ubaid.jobdash.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code ai:} configuration block: whether resume-match scoring via the local Claude
 * CLI is enabled, where the binary lives, which model to invoke, batching/concurrency tuning,
 * the per-invocation timeout, and the description-truncation length.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        boolean enabled,
        String cliPath,
        String model,
        int batchSize,
        int concurrency,
        Duration timeout,
        int maxDescriptionChars,
        boolean usOnly,
        int locationBatchSize
) {
}
