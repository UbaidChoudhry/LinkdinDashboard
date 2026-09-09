package com.ubaid.jobdash.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Binds the {@code sweep:} configuration block: pacing, request budgets, circuit-breaker
 * cooldown behaviour, and the post-collection detail-fetch phase for the LinkedIn scraping sweep.
 * <p>
 * Registered via {@link SweepPropertiesConfiguration} rather than {@code @Component}, so this
 * package stays self-registering without requiring changes to the application's main class.
 */
@ConfigurationProperties(prefix = "sweep")
public record SweepProperties(Pacing pacing, Budget budget, Breaker breaker, @DefaultValue Detail detail) {

    /** Layer A — inter-request pacing bounds. Delay is uniform random in [minDelay, maxDelay]. */
    public record Pacing(Duration minDelay, Duration maxDelay) {
    }

    /** Layer B/C — per-run cap and rolling 24h budget. */
    public record Budget(int perRun, int perRollingDay, int testModePageCap) {
    }

    /** Circuit-breaker cooldown and soft-failure tuning. */
    public record Breaker(Duration openDuration, Duration maxOpenDuration, int softFailureThreshold) {
    }

    /**
     * The detail-fetch phase that follows a LinkedIn collection: whether it runs at all, and how
     * many job-detail requests one run may spend ({@code 0} = no cap beyond the shared budget).
     */
    public record Detail(@DefaultValue("true") boolean enabled, @DefaultValue("0") int maxPerRun) {
    }
}
