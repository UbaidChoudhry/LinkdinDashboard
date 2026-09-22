package com.ubaid.jobdash.domain;

import java.time.Instant;
import java.util.List;

/**
 * What {@code apply.CompanyLinkFinder} found for one LinkedIn job on the employer's own careers
 * site: the posting's {@code url} (empty when nothing matched), a 0-100 {@code confidence} that it
 * is the same job, which fields agreed ({@code matchedOn}: title, location, description,
 * requisition, date), and Claude's one-sentence {@code note} on the comparison.
 */
public record CompanyLinkMatch(
        long jobId,
        String url,
        int confidence,
        List<String> matchedOn,
        String note,
        Instant checkedAt
) {
    public boolean found() {
        return url != null && !url.isBlank();
    }
}
