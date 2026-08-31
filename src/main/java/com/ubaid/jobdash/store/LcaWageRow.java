package com.ubaid.jobdash.store;

/**
 * One pre-aggregated row of local DOL LCA disclosure data, keyed by
 * {@code (employerKey, socCode, state)}. {@code state} is {@code ""} for the national /
 * any-state aggregate. Wage percentiles are annualized USD; {@code latestDataDate} is ISO-8601.
 *
 * <p>{@code employerDisplay} is the first raw {@code EMPLOYER_NAME} seen for the group (e.g.
 * {@code AMAZON.COM SERVICES LLC}) — the human-readable entity behind the normalized
 * {@code employerKey}. May be null for rows imported before this column existed.
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
        String latestDataDate,
        String employerDisplay
) {
}
