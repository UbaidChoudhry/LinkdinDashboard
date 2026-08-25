package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.store.CompanyBlocklistRepository;

import java.time.Instant;

/** One row of {@code GET /api/filters/companies}. */
public record CompanyBlocklistResponse(String company, String reason, String evidence, Instant addedAt) {

    public static CompanyBlocklistResponse from(CompanyBlocklistRepository.BlockedCompany company) {
        return new CompanyBlocklistResponse(company.company(), company.reason(), company.evidence(), company.addedAt());
    }
}
