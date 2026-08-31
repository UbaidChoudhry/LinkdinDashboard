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
        MonthlyCap monthlyCap,
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

    /**
     * Per-source rolling 30-day ceilings. Providers whose free tier is billed monthly need this
     * as well as a daily cap — 30 days of "safe" daily usage can still exceed a monthly quota.
     * A value of {@code <= 0} means that source has no monthly limit.
     */
    public record MonthlyCap(int adzuna, int h1bapi) {
    }

    /** Adzuna API credentials; blank when unconfigured (the source then stays disabled). */
    public record Adzuna(String appId, String appKey) {
    }

    /**
     * h1bapi.com API key. <b>Optional</b> — the free tier (20 requests/day, last two fiscal
     * years) works unauthenticated; a key only raises the ceiling.
     */
    public record H1bApi(String apiKey) {
    }

    /**
     * The DOL LCA import job. {@code importPath} is a single {@code .xlsx} <em>or</em> a
     * directory of them, and is blank on a normal boot (the import only runs when it is set).
     * {@code deleteAfterImport} removes each spreadsheet once it has actually contributed rows —
     * they are ~80 MB apiece and are of no further use once aggregated into {@code lca_wage}.
     */
    public record Lca(String importPath, boolean deleteAfterImport) {
    }
}
