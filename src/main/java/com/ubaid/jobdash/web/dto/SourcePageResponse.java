package com.ubaid.jobdash.web.dto;

import java.util.List;

/** Body of {@code GET /api/sources} — one page of the catalog plus the total matching count. */
public record SourcePageResponse(List<AtsCompanyResponse> items, long total) {
}
