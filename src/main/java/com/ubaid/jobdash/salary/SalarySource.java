package com.ubaid.jobdash.salary;

import java.util.Optional;

/**
 * One provider of salary data. Implementations are tried in a fixed cascade order by
 * {@link SalaryEnrichmentService}; the first to return a non-empty {@link SalaryResult} wins.
 * <p>
 * Implementations must never throw from {@link #lookup}: a provider that is misconfigured,
 * rate-limited, unreachable or returns garbage returns {@link Optional#empty()}.
 */
public interface SalarySource {

    /** Stable provenance name: {@code "lca"}, {@code "adzuna"} or {@code "h1bapi"}. */
    String name();

    /** Best available salary band for {@code q}, or empty if this source has nothing. */
    Optional<SalaryResult> lookup(SalaryLookup q);
}
