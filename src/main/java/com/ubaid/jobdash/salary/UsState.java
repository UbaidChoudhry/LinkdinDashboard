package com.ubaid.jobdash.salary;

import java.util.List;
import java.util.Map;

/**
 * Best-effort parser from a LinkedIn location string to a 2-letter USPS state code.
 * <p>
 * Handles the common shapes seen on job cards: {@code "Austin, Texas, United States"},
 * {@code "New York, NY"}, {@code "Greater Seattle Area"}, {@code "San Francisco Bay Area"},
 * {@code "United States"}, {@code "Remote"}. Returns {@code ""} when no single state can be
 * determined (nationwide, remote, non-US, or unrecognized).
 */
public final class UsState {

    private UsState() {
    }

    /** Full state (and DC) name -> USPS code. */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("alabama", "AL"), Map.entry("alaska", "AK"), Map.entry("arizona", "AZ"),
            Map.entry("arkansas", "AR"), Map.entry("california", "CA"), Map.entry("colorado", "CO"),
            Map.entry("connecticut", "CT"), Map.entry("delaware", "DE"),
            Map.entry("district of columbia", "DC"), Map.entry("washington dc", "DC"),
            Map.entry("florida", "FL"), Map.entry("georgia", "GA"), Map.entry("hawaii", "HI"),
            Map.entry("idaho", "ID"), Map.entry("illinois", "IL"), Map.entry("indiana", "IN"),
            Map.entry("iowa", "IA"), Map.entry("kansas", "KS"), Map.entry("kentucky", "KY"),
            Map.entry("louisiana", "LA"), Map.entry("maine", "ME"), Map.entry("maryland", "MD"),
            Map.entry("massachusetts", "MA"), Map.entry("michigan", "MI"), Map.entry("minnesota", "MN"),
            Map.entry("mississippi", "MS"), Map.entry("missouri", "MO"), Map.entry("montana", "MT"),
            Map.entry("nebraska", "NE"), Map.entry("nevada", "NV"), Map.entry("new hampshire", "NH"),
            Map.entry("new jersey", "NJ"), Map.entry("new mexico", "NM"), Map.entry("new york", "NY"),
            Map.entry("north carolina", "NC"), Map.entry("north dakota", "ND"), Map.entry("ohio", "OH"),
            Map.entry("oklahoma", "OK"), Map.entry("oregon", "OR"), Map.entry("pennsylvania", "PA"),
            Map.entry("rhode island", "RI"), Map.entry("south carolina", "SC"),
            Map.entry("south dakota", "SD"), Map.entry("tennessee", "TN"), Map.entry("texas", "TX"),
            Map.entry("utah", "UT"), Map.entry("vermont", "VT"), Map.entry("virginia", "VA"),
            Map.entry("washington", "WA"), Map.entry("west virginia", "WV"), Map.entry("wisconsin", "WI"),
            Map.entry("wyoming", "WY"));

    private static final java.util.Set<String> CODES = java.util.Set.copyOf(NAMES.values());

    /** Well-known metro-area phrases that map unambiguously to one state. */
    private static final List<Map.Entry<String, String>> METROS = List.of(
            Map.entry("san francisco bay area", "CA"), Map.entry("greater los angeles", "CA"),
            Map.entry("greater san diego", "CA"), Map.entry("greater sacramento", "CA"),
            Map.entry("silicon valley", "CA"), Map.entry("greater seattle", "WA"),
            Map.entry("greater boston", "MA"), Map.entry("greater chicago", "IL"),
            Map.entry("greater philadelphia", "PA"), Map.entry("greater pittsburgh", "PA"),
            Map.entry("greater houston", "TX"), Map.entry("dallas-fort worth", "TX"),
            Map.entry("greater austin", "TX"), Map.entry("austin, texas metropolitan area", "TX"),
            Map.entry("greater minneapolis", "MN"), Map.entry("greater denver", "CO"),
            Map.entry("greater phoenix", "AZ"), Map.entry("greater atlanta", "GA"),
            Map.entry("miami-fort lauderdale", "FL"), Map.entry("greater orlando", "FL"),
            Map.entry("greater tampa bay", "FL"),
            Map.entry("new york city metropolitan area", "NY"), Map.entry("greater new york", "NY"),
            Map.entry("los angeles metropolitan area", "CA"),
            Map.entry("washington dc-baltimore area", "DC"), Map.entry("washington d.c. metro area", "DC"),
            Map.entry("research triangle", "NC"), Map.entry("portland, oregon metropolitan area", "OR"),
            Map.entry("greater nashville", "TN"), Map.entry("salt lake city metropolitan area", "UT"));

    /**
     * The full state (and DC) names this parser knows, lowercase. Exposed read-only for callers
     * that need to recognise a state ANYWHERE in a free-text string rather than resolve one
     * specific code. Currently unused: the location classifier that once relied on it was replaced
     * by {@code source/location/LocationClassifier}, which asks Claude instead.
     */
    public static java.util.Set<String> stateNames() {
        return NAMES.keySet();
    }

    /** The 2-letter USPS codes this parser knows. Read-only; see {@link #stateNames()}. */
    public static java.util.Set<String> stateCodes() {
        return CODES;
    }

    /**
     * Returns the 2-letter USPS code for the state named in {@code location}, or {@code ""} if
     * indeterminate. Comma-separated components are checked first (both full names and 2-letter
     * codes), then the whole string is matched against known metro-area phrases.
     */
    public static String fromLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String lower = location.toLowerCase().trim();

        String[] parts = lower.split(",");
        // 2-letter codes first: "Washington, DC" must resolve to DC, not WA from "washington".
        for (String raw : parts) {
            String upper = raw.trim().toUpperCase();
            if (upper.length() == 2 && CODES.contains(upper)) {
                return upper;
            }
        }
        for (String raw : parts) {
            String part = raw.trim();
            if (NAMES.containsKey(part)) {
                return NAMES.get(part);
            }
        }

        // Substring match on the whole string for multi-word state names embedded without a
        // clean comma boundary, then metro phrases.
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            if (e.getKey().contains(" ") && lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : METROS) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }
}
