package com.ubaid.jobdash.sweep;

import java.util.List;

/**
 * Parameters for one ATS run, as accepted by {@link AtsSweepService#run}. The ATS equivalent of
 * {@link SweepRunRequest} - deliberately narrower, since sharding/test-mode/page-cap are
 * LinkedIn-specific concepts that don't apply to a company-board crawl.
 *
 * @param keywords plain search keywords, matched locally by {@link com.ubaid.jobdash.source.LocalFilter}
 *                 (Greenhouse/Lever) or server-side (Workday).
 * @param location free-text location filter; blank means no location filtering.
 * @param hours    recency window in hours; postings with no known date are never filtered out on
 *                 this basis alone (see {@code LocalFilter.withinRecency}).
 * @param atsNames the selected ATS sources for this run, e.g. {@code ["greenhouse", "lever"]}.
 * @param usOnly   when true, every posting whose location is not identifiably in the United
 *                 States is dropped before it is stored. Applied centrally in
 *                 {@link AtsSweepService}, NOT inside the individual sources, so it covers
 *                 Workday too - Workday filters keywords server-side and does no local location
 *                 filtering of its own, which is exactly how foreign postings were getting in.
 */
public record AtsRunRequest(
        String keywords,
        String location,
        int hours,
        List<String> atsNames,
        boolean usOnly
) {
}
