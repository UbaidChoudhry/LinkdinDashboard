package com.ubaid.jobdash.store;

/**
 * One pre-aggregated row of local DOL LCA disclosure data, keyed by
 * {@code (employerKey, socCode, state)}. {@code state} is {@code ""} for the national /
 * any-state aggregate. Wage percentiles are annualized USD; {@code latestDataDate} is ISO-8601.
 */
public record LcaWageRow(
        String employerKey,
        String socCode,
        String state,
        Double wageP25,
        Double wageP50,
        Double wageP75,
        Double wageMax,
        int sampleCount,
        String latestDataDate
) {
}
