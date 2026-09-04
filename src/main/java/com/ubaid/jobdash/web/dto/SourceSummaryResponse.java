package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.store.AtsCompanyRepository.CatalogCounts;

import java.util.Map;

/** Body of {@code GET /api/sources/summary} — catalog totals by ATS and by status. */
public record SourceSummaryResponse(Map<String, Long> byAts, Map<String, Long> byStatus) {

    public static SourceSummaryResponse from(CatalogCounts counts) {
        return new SourceSummaryResponse(counts.byAts(), counts.byStatus());
    }
}
