package com.ubaid.jobdash.source;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

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

    /** Country-level ways of asking for the US, which a substring match cannot serve. */
    private static final Set<String> US_COUNTRY_TERMS =
            Set.of("united states", "united states of america", "usa", "us", "u.s.", "u.s.a.", "america");

    /**
     * True when {@code jobLocation} satisfies the requested {@code location}; true when the
     * request is blank (no filter).
     *
     * <p>A country-level request is handled by {@link UsLocation}, not by substring: a board
     * writes "New York, NY", which does <em>not</em> contain the string "United States", so a
     * naive match would throw away nearly every genuine US posting while claiming to filter for
     * them. Anything more specific ("New York", "Austin") stays a plain substring match.
     */
    public static boolean matchesLocation(String jobLocation, String location) {
        if (location == null || location.isBlank()) {
            return true;
        }
        if (US_COUNTRY_TERMS.contains(location.toLowerCase(Locale.ROOT).trim())) {
            return UsLocation.isUnitedStates(jobLocation);
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
