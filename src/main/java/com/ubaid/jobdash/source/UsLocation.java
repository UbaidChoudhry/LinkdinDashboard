package com.ubaid.jobdash.source;

import com.ubaid.jobdash.salary.UsState;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a job board's location string refers to the United States.
 *
 * <p>ATS boards are worldwide and their location formats disagree: Greenhouse says
 * {@code "Paris, France"} or just {@code "Brazil"}, Lever says {@code "Vancouver, BC"}, and
 * Workday writes country FIRST — {@code "Israel, Yokneam"}. So the whole string is examined,
 * never just its last component.
 *
 * <p>Order matters. A named non-US country is rejected <b>before</b> any US signal is considered,
 * because two-letter country codes collide with USPS state codes: {@code DE} is both Germany and
 * Delaware, {@code CA} both Canada and California, {@code IN} both India and Indiana. Checking
 * for a state first would quietly classify Munich and Bangalore as American.
 */
public final class UsLocation {

    private UsLocation() {
    }

    /** Phrases that name the US outright, in the forms boards actually use. */
    private static final Set<String> US_PHRASES = Set.of(
            "united states", "united states of america", "u.s.a", "u.s.", "america");

    /**
     * Standalone tokens that mean the US. Matched as whole tokens rather than as substrings,
     * because boards separate them with every punctuation character going - real examples from
     * live data include {@code "US, Remote"}, {@code "US - Austin, TX"}, {@code "USA.VA.Reston"}
     * and {@code "USA, CA, Pleasanton"}. A padded-space check missed all but one of those.
     */
    private static final Set<String> US_TOKENS = Set.of("us", "usa");

    /**
     * Major US cities that boards routinely list with no state or country at all
     * ({@code "San Francisco"}, {@code "New York"}). Without these a US-only filter throws away
     * obviously-domestic postings.
     *
     * <p>Accepted tradeoff: a few of these names exist abroad too - San Jose is also in Costa
     * Rica - so this trades a little precision for a lot of recall. It is consulted LAST, after
     * every named foreign country has already been ruled out, which removes the common case.
     */
    private static final Set<String> US_CITIES = Set.of(
            "san francisco", "san jose", "new york", "new york city", "brooklyn", "los angeles",
            "chicago", "seattle", "boston", "austin", "denver", "atlanta", "dallas", "houston",
            "miami", "philadelphia", "phoenix", "portland", "san diego", "detroit", "minneapolis",
            "pittsburgh", "nashville", "charlotte", "raleigh", "columbus", "indianapolis",
            "kansas city", "las vegas", "salt lake city", "sacramento", "san antonio", "orlando",
            "tampa", "st. louis", "saint louis", "cincinnati", "cleveland", "milwaukee",
            "baltimore", "bellevue", "redmond", "mountain view", "palo alto", "sunnyvale",
            "santa clara", "cupertino", "menlo park", "boulder", "ann arbor", "arlington",
            "reston", "mclean", "foster city", "scottsdale", "pleasanton", "ashburn");

    /**
     * Country names that appear in real board data. Not exhaustive as a world list, but it does
     * not need to be: anything not positively identified as the US is rejected anyway. This
     * exists to stop the state-code fallback misreading a foreign location, so it prioritises
     * countries with a code that collides with a US state.
     */
    private static final Set<String> NON_US_COUNTRIES = Set.of(
            "canada", "mexico", "brazil", "argentina", "chile", "colombia", "peru", "uruguay",
            "united kingdom", "england", "scotland", "wales", "northern ireland", "ireland",
            "france", "germany", "spain", "portugal", "italy", "netherlands", "belgium",
            "switzerland", "austria", "sweden", "norway", "denmark", "finland", "iceland",
            "poland", "czechia", "czech republic", "slovakia", "hungary", "romania", "bulgaria",
            "greece", "croatia", "serbia", "slovenia", "estonia", "latvia", "lithuania",
            "ukraine", "russia", "turkey", "israel", "united arab emirates", "saudi arabia",
            "qatar", "egypt", "morocco", "nigeria", "kenya", "ghana", "south africa",
            "india", "pakistan", "bangladesh", "sri lanka", "china", "hong kong", "taiwan",
            "japan", "south korea", "korea", "singapore", "malaysia", "indonesia", "thailand",
            "vietnam", "philippines", "australia", "new zealand", "costa rica", "panama",
            "guatemala", "dominican republic", "puerto rico", "bermuda", "luxembourg", "malta",
            "cyprus", "moldova", "georgia country", "armenia", "kazakhstan", "uzbekistan");

    /** Canadian provinces, whose two-letter codes are the most common state-code collision. */
    private static final Set<String> CANADIAN_PROVINCES = Set.of(
            "ontario", "quebec", "british columbia", "alberta", "manitoba", "saskatchewan",
            "nova scotia", "new brunswick", "newfoundland", "prince edward island");

    /**
     * True when {@code location} is identifiably in the United States. A blank or unrecognisable
     * location returns false: this backs a filter the user asked to be <em>hard</em>, so
     * "can't tell" is excluded rather than let through.
     */
    public static boolean isUnitedStates(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        String lower = " " + location.toLowerCase(Locale.ROOT).trim() + " ";

        for (String country : NON_US_COUNTRIES) {
            if (containsWord(lower, country)) {
                return false;
            }
        }
        for (String province : CANADIAN_PROVINCES) {
            if (containsWord(lower, province)) {
                return false;
            }
        }
        for (String phrase : US_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        // Only now are the token/state lookups safe - every colliding foreign name is ruled out.
        Set<String> tokens = tokenize(lower);
        for (String token : US_TOKENS) {
            if (tokens.contains(token)) {
                return true;
            }
        }
        for (String code : UsState.stateCodes()) {
            if (tokens.contains(code.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // Whole-string state-name match. UsState.fromLocation only finds SINGLE-word state names
        // on a comma boundary, so it misses "California - Remote" and "Virginia - Mclean", both
        // of which are real formats in live board data.
        for (String name : UsState.stateNames()) {
            if (containsWord(lower, name)) {
                return true;
            }
        }
        for (String city : US_CITIES) {
            if (containsWord(lower, city)) {
                return true;
            }
        }
        return !UsState.fromLocation(location).isEmpty();
    }

    /** Lowercase alphanumeric tokens, split on every non-alphanumeric separator. */
    private static Set<String> tokenize(String lower) {
        Set<String> tokens = new java.util.HashSet<>();
        for (String token : lower.split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Whole-word containment. A plain {@code contains} would match "india" inside "indiana" and
     * "chile" inside a longer word, throwing away legitimate US postings.
     */
    private static boolean containsWord(String paddedLower, String term) {
        int from = 0;
        while (true) {
            int at = paddedLower.indexOf(term, from);
            if (at < 0) {
                return false;
            }
            char before = paddedLower.charAt(at - 1);
            int afterIndex = at + term.length();
            char after = afterIndex < paddedLower.length() ? paddedLower.charAt(afterIndex) : ' ';
            if (!Character.isLetter(before) && !Character.isLetter(after)) {
                return true;
            }
            from = at + 1;
        }
    }
}
