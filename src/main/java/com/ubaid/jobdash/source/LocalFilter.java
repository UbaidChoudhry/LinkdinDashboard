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
     * <p>A country-level request ("United States", "USA") imposes no filter here at all — that
     * judgement belongs to {@code source/location/LocationClassifier}, which asks Claude. Anything
     * more specific ("New York", "Austin") stays a plain substring match.
     */
    public static boolean matchesLocation(String jobLocation, String location) {
        if (location == null || location.isBlank()) {
            return true;
        }
        if (US_COUNTRY_TERMS.contains(location.toLowerCase(Locale.ROOT).trim())) {
            // A country-level request is not a city filter. Whether a posting is in the US is
            // decided by LocationClassifier after collection, so asking for "United States" here
            // means "no location narrowing" rather than a substring test - which would otherwise
            // discard "New York, NY", since that string does not contain "united states".
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

    /**
     * {@link #withinRecency} for a source that only knows the calendar <em>day</em> a job was
     * posted (Workday's {@code startDate}), given as that day's start in UTC. The whole day counts
     * as inside the window if any part of it is: a job posted "yesterday" must survive a 24-hour
     * window even though its midnight timestamp is more than 24 hours old.
     */
    public static boolean postedDayWithinRecency(Instant postedDayStartUtc, int hours, Clock clock) {
        if (postedDayStartUtc == null || hours <= 0) {
            return true;
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(hours));
        return postedDayStartUtc.plus(Duration.ofDays(1)).isAfter(cutoff);
    }
}
