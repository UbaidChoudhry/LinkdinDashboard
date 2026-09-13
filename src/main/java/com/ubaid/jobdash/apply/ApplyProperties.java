package com.ubaid.jobdash.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code apply:} configuration block: whether "Apply with Claude" is enabled, which
 * model to invoke, the per-job timeout/turn/budget ceilings for the Claude-in-Chrome CLI call,
 * and the description-truncation length used by {@link ApplyPromptBuilder}.
 */
@ConfigurationProperties(prefix = "apply")
public record ApplyProperties(
        boolean enabled,
        String model,
        Duration timeout,
        int maxTurns,
        double maxBudgetUsd,
        int maxDescriptionChars
) {
}
