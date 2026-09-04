package com.ubaid.jobdash.source;

/**
 * What one {@link JobSource#fetch} call asks for: the run's keyword/location/recency filters,
 * plus the coordinates of the specific company board to fetch. {@code host}/{@code site} are
 * only meaningful for Workday (Greenhouse/Lever address a board by {@code slug} alone) and are
 * null otherwise.
 */
public record SourceQuery(
        String keywords,
        String location,
        int hours,
        String slug,
        String companyName,
        String host,
        String site
) {
}
