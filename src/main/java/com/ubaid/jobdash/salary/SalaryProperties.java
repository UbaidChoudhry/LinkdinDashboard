package com.ubaid.jobdash.salary;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code salary:} configuration block: the estimate-cache TTL and staleness window,
 * pacing / daily-cap tuning for the external salary APIs, per-provider credentials, and the
 * local LCA import file path.
 * <p>
 * Registered via {@link SalaryConfiguration} rather than {@code @Component}, mirroring the
 * {@code SweepPropertiesConfiguration} pattern, so this package stays self-registering without
 * requiring changes to the application's main class.
 */
@ConfigurationProperties(prefix = "salary")
public record SalaryProperties(
        boolean enabled,
        Duration cacheTtl,
        Duration maxDataAge,
        Pacing pacing,
        DailyCap dailyCap,
        Adzuna adzuna,
        H1bApi h1bApi,
        Lca lca
) {

    /** Minimum spacing between successive outbound calls through the salary rate limiter. */
    public record Pacing(Duration minDelay) {
    }

    /** Per-source rolling 24h request ceilings. */
    public record DailyCap(int adzuna, int h1bapi) {
    }

    /** Adzuna API credentials; blank when unconfigured (the source then stays disabled). */
    public record Adzuna(String appId, String appKey) {
    }

    /** h1bdata-style API key; blank when unconfigured. */
    public record H1bApi(String apiKey) {
    }

    /** Path to the DOL LCA disclosure spreadsheet to import; blank when unconfigured. */
    public record Lca(String importFile) {
    }
}
