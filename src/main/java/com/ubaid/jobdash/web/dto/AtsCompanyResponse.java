package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.AtsCompany;

import java.time.Instant;

/** One row of the ATS company catalog, as returned by {@code GET /api/sources}. */
public record AtsCompanyResponse(
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

    public static AtsCompanyResponse from(AtsCompany c) {
        return new AtsCompanyResponse(c.id(), c.ats(), c.slug(), c.company(), c.host(), c.site(),
                c.enabled(), c.status(), c.consecutiveFailures(), c.lastCheckedAt(), c.lastOkAt(),
                c.lastJobCount(), c.addedAt());
    }
}
