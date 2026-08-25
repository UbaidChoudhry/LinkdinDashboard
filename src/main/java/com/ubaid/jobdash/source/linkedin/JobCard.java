package com.ubaid.jobdash.source.linkedin;

import java.time.LocalDate;

/**
 * A single job posting parsed from a LinkedIn public job-search result card.
 */
public record JobCard(
        long jobId,
        String title,
        String company,
        String location,
        LocalDate postedDate,
        String jobUrl,
        String companyUrl
) {
}
