package com.ubaid.jobdash.salary;

import java.time.LocalDate;

/**
 * A single salary band returned by a {@link SalarySource}.
 *
 * @param salaryMin    bottom of the annualized USD band (may be null if only a point estimate)
 * @param salaryMax    top of the annualized USD band — this is the value the two-bucket job
 *                      sort orders on
 * @param currency     ISO currency code; always {@code "USD"} for the sources implemented today
 * @param dataDate     the date the underlying data point is from, used for the 3-year staleness
 *                      rule in {@link SalaryEnrichmentService}; may be null when a source cannot
 *                      determine it
 * @param sampleCount  how many underlying records the band was derived from, when known
 * @param source       provenance tag: {@code "lca"}, {@code "adzuna"} or {@code "h1bapi"}
 * @param matchedEntity the human-readable entity this band was matched against — the LCA
 *                      {@code employer_display} (or its {@code employer_key} when no display
 *                      name is stored); {@code null} for sources that estimate by title and
 *                      location rather than by a specific employer (Adzuna, h1bapi)
 */
public record SalaryResult(
        Double salaryMin,
        Double salaryMax,
        String currency,
        LocalDate dataDate,
        Integer sampleCount,
        String source,
        String matchedEntity
) {
}
