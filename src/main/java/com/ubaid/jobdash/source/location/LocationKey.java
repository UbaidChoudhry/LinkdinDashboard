package com.ubaid.jobdash.source.location;

import java.util.Locale;

/**
 * Normalises a free-form job location into the cache key used by {@code location_verdict}.
 *
 * <p>Deliberately conservative: lowercase, collapse internal whitespace, trim. It only collapses
 * differences that cannot change which country a string refers to — {@code "San Francisco Bay Area "}
 * and {@code "san francisco bay area"} are the same place and should cost one classification, not
 * two. It does NOT strip punctuation, because punctuation carries meaning here:
 * {@code "USA.VA.Reston"} and {@code "US, Remote"} both depend on their separators.
 *
 * <p>Follows the convention set by {@code salary/CompanyKey} and {@code salary/TitleKey} — key
 * normalisation is a standalone pure utility, never inlined into a repository.
 */
public final class LocationKey {

    private LocationKey() {
    }

    /** Returns the cache key for {@code location}, or {@code ""} when there is nothing to key on. */
    public static String of(String location) {
        if (location == null) {
            return "";
        }
        return location.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
