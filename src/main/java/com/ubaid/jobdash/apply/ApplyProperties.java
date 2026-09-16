package com.ubaid.jobdash.apply;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code apply:} configuration block: whether "Apply with Claude" is enabled, which
 * model to invoke, the per-job timeout/turn/budget ceilings for the Claude-in-Chrome CLI call,
 * the description-truncation length used by {@link ApplyPromptBuilder}, the idle-activity
 * ceiling ({@code idleTimeout}) that kills a job whose CLI call has gone silent (e.g. a frozen
 * native file-picker dialog), and the directory ({@code transcriptDir}) where each job's
 * per-job stream-json transcript is written.
 */
@ConfigurationProperties(prefix = "apply")
public record ApplyProperties(
        boolean enabled,
        String model,
        Duration timeout,
        int maxTurns,
        double maxBudgetUsd,
        int maxDescriptionChars,
        Duration idleTimeout,
        String transcriptDir,
        String effort
) {
}
