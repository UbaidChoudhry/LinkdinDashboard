package com.ubaid.jobdash.domain;

import java.time.Instant;

/**
 * One row of {@code ats_company} — a catalog entry for a company's Greenhouse/Lever/Workday
 * board, whether hand-added, seeded, or bulk-imported. See {@code V6__ats_sources.sql} for the
 * catalog-vs-selection split this table implements.
 */
public record AtsCompany(
        long id,
        String ats,
        String slug,
        String company,
        String host,
        String site,
        boolean enabled,
        String status,
        int consecutiveFailures,
        Instant lastCheckedAt,
        Instant lastOkAt,
        Integer lastJobCount,
        Instant addedAt
) {
}
