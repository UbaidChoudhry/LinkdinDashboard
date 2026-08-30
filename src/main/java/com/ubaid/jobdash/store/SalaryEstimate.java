package com.ubaid.jobdash.store;

import java.time.Instant;

/**
 * One row of the {@code salary_estimate} cache: an annualized USD salary band for a normalized
 * {@code (companyKey, titleKey)} pair, plus provenance.
 *
 * <p>{@code source} is one of {@code 'lca'}, {@code 'adzuna'}, {@code 'h1bapi'}, or {@code 'none'}
 * ({@code 'none'} meaning "we looked and found nothing" — still cached so we don't re-look every
 * time). {@code dataDate} is the ISO-8601 date the underlying data point is from (for the
 * 3-year staleness rule); {@code fetchedAt} is when this row was recorded (for the TTL).
 */
public record SalaryEstimate(
        String companyKey,
        String titleKey,
        Double salaryMin,
        Double salaryMax,
        String currency,
        String source,
        String dataDate,
        Integer sampleCount,
        Instant fetchedAt
) {
}
