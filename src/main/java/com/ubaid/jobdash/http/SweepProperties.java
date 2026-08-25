package com.ubaid.jobdash.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code sweep:} configuration block: pacing, request budgets, and circuit-breaker
 * cooldown behaviour for the LinkedIn scraping sweep.
 * <p>
 * Registered via {@link SweepPropertiesConfiguration} rather than {@code @Component}, so this
 * package stays self-registering without requiring changes to the application's main class.
 */
@ConfigurationProperties(prefix = "sweep")
public record SweepProperties(Pacing pacing, Budget budget, Breaker breaker) {

    /** Layer A — inter-request pacing bounds. Delay is uniform random in [minDelay, maxDelay]. */
    public record Pacing(Duration minDelay, Duration maxDelay) {
    }

    /** Layer B/C — per-run cap and rolling 24h budget. */
    public record Budget(int perRun, int perRollingDay, int testModePageCap) {
    }

    /** Circuit-breaker cooldown and soft-failure tuning. */
    public record Breaker(Duration openDuration, Duration maxOpenDuration, int softFailureThreshold) {
    }
}
