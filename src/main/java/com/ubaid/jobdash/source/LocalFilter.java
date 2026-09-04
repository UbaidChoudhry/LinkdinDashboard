package com.ubaid.jobdash.source;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Local keyword/location/recency filtering shared by {@code GreenhouseJobSource} and
 * {@code LeverJobSource} — both return a company's entire board in one request, so unlike
 * Workday (which filters server-side via {@code searchText}) they must filter after the fact.
 */
public final class LocalFilter {

    private LocalFilter() {
    }

    /** True if any whitespace-separated token of {@code keywords} appears in {@code title}. */
    public static boolean matchesKeywords(String title, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return true;
        }
        if (title == null) {
            return false;
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        for (String token : keywords.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank() && lowerTitle.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive substring match; true when {@code location} is blank (no filter). */
    public static boolean matchesLocation(String jobLocation, String location) {
        if (location == null || location.isBlank()) {
            return true;
        }
        if (jobLocation == null) {
            return false;
        }
        return jobLocation.toLowerCase(Locale.ROOT).contains(location.toLowerCase(Locale.ROOT));
    }

    /**
     * True when {@code postedAt} falls within {@code hours} of now, or when {@code postedAt} is
     * null — a posting with no known date is never filtered out on recency grounds alone.
     */
    public static boolean withinRecency(Instant postedAt, int hours, Clock clock) {
        if (postedAt == null || hours <= 0) {
            return true;
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(hours));
        return !postedAt.isBefore(cutoff);
    }
}
