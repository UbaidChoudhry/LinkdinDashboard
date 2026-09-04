package com.ubaid.jobdash.source.ats;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code ats:} configuration block: pacing and per-source daily ceilings for the ATS
 * job-board APIs, the guard rails bounding how much one run may fetch, the dead-slug retirement
 * threshold, and the slug-catalog import path.
 * <p>
 * Registered via {@link AtsConfiguration} rather than {@code @Component}, mirroring the
 * {@code SalaryConfiguration} / {@code SweepPropertiesConfiguration} pattern, so this package
 * stays self-registering without touching the application's main class.
 */
@ConfigurationProperties(prefix = "ats")
public record AtsProperties(
        boolean enabled,
        Pacing pacing,
        int maxCompaniesPerRun,
        Workday workday,
        DailyCap dailyCap,
        int deadSlugThreshold,
        long maxResponseBytes,
        Slugs slugs
) {

    /** Minimum spacing between successive outbound calls through {@code AtsRateLimiter}. */
    public record Pacing(Duration minDelay) {
    }

    /**
     * Workday-specific bounds. Greenhouse and Lever return a company's entire board in one
     * request; Workday's search page carries no description and only a relative "Posted 30+
     * Days Ago", so every job kept costs an additional detail request. {@code maxDetailsPerRun}
     * bounds that second phase across the whole run.
     */
    public record Workday(int maxDetailsPerRun, int pageSize) {
    }

    /**
     * Per-source rolling 24h request ceilings, counted from {@code external_request_log}. These
     * are deliberately generous: unlike the LinkedIn guest scrape, these are public documented
     * JSON APIs. They exist to bound runaway loops, not to ration a scarce quota.
     */
    public record DailyCap(int greenhouse, int lever, int workday) {
    }

    /**
     * The slug-catalog import job. {@code importPath} is a local JSON file <em>or</em> a URL,
     * and is blank on a normal boot (the import only runs when it is set). {@code pruneDead}
     * additionally deletes rows already marked dead instead of leaving them disabled.
     */
    public record Slugs(String importPath, boolean pruneDead) {
    }
}
