package com.ubaid.jobdash.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Location strings here are the real formats the three boards emit, taken from live responses:
 * Greenhouse writes "Paris, France" or bare "Brazil", Lever "Vancouver, BC", and Workday puts the
 * country FIRST ("Israel, Yokneam").
 */
class UsLocationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "United States", "New York, NY", "San Francisco, CA", "Seattle, Washington, United States",
            "Austin, Texas", "Remote - US", "Remote, US", "USA", "Boston, MA", "Denver, CO",
            "Washington, DC", "New York City Metropolitan Area", "Chicago, Illinois"
    })
    void acceptsUnitedStatesLocations(String location) {
        assertThat(UsLocation.isUnitedStates(location)).as(location).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Paris, France", "Brazil", "China", "London, United Kingdom", "Vancouver, BC",
            "Israel, Yokneam", "India, Bangalore", "Toronto, Ontario", "Munich, Germany",
            "Tokyo, Japan", "Singapore", "Sydney, Australia", "Dublin, Ireland",
            "Mexico City, Mexico", "Amsterdam, Netherlands"
    })
    void rejectsNonUnitedStatesLocations(String location) {
        assertThat(UsLocation.isUnitedStates(location)).as(location).isFalse();
    }

    /**
     * The collision this class exists for: DE is Germany AND Delaware, CA is Canada AND
     * California, IN is India AND Indiana. Checking for a US state before ruling out a named
     * country would classify all three foreign cities as American.
     */
    @Test
    void namedCountryBeatsACollidingStateCode() {
        assertThat(UsLocation.isUnitedStates("Munich, Germany")).isFalse();
        assertThat(UsLocation.isUnitedStates("Bangalore, India")).isFalse();
        assertThat(UsLocation.isUnitedStates("Vancouver, Canada")).isFalse();
        // ...while the genuine US states still resolve.
        assertThat(UsLocation.isUnitedStates("Wilmington, DE")).isTrue();
        assertThat(UsLocation.isUnitedStates("Indianapolis, IN")).isTrue();
    }

    /** "india" sits inside "indiana"; a naive substring check would drop a real US state. */
    @Test
    void doesNotMistakeAStateForACountryItContains() {
        assertThat(UsLocation.isUnitedStates("Indianapolis, Indiana")).isTrue();
    }

    @Test
    void treatsUnknownAndBlankAsNotUnitedStates() {
        assertThat(UsLocation.isUnitedStates(null)).isFalse();
        assertThat(UsLocation.isUnitedStates("")).isFalse();
        assertThat(UsLocation.isUnitedStates("Remote")).as("bare 'Remote' names no country").isFalse();
    }

    // --- formats taken verbatim from live board data ------------------------------------------
    // Every string below was pulled out of the real job_listing table, not invented. Four of them
    // ("California - Remote", "Virginia - Mclean", "San Francisco", "US, Remote") were wrongly
    // rejected by the first version of this class, which is why they are pinned here.

    @ParameterizedTest
    @ValueSource(strings = {
            "Austin, Texas, United States of America", "California - Remote", "Virginia - Mclean",
            "US - Ashburn, VA", "US - Foster City, CA", "US, CA, Santa Clara", "US, Remote",
            "USA, CO, Boulder", "USA.VA.Reston", "San Francisco", "San Jose", "New York",
            "Scottsdale, Arizona, United States of America"
    })
    void acceptsRealUsBoardFormats(String location) {
        assertThat(UsLocation.isUnitedStates(location)).as(location).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BR - Remote - Brazil", "Canada - Toronto", "Canada, BC, Vancouver", "China, Shanghai",
            "GB - Basingstoke, United Kingdom", "Germany - Munich", "IN - Bengaluru, India",
            "India - Hyderabad", "Ireland, Dublin", "Israel, Yokneam", "Italy - Rome",
            "Mexico - Mexico City", "Netherlands - Amsterdam", "Palestine, Rawabi",
            "Poland - Warszawa", "SG - Singapore", "Spain - Barcelona", "Sweden, Stockholm",
            "United Kingdom - London", "Bangalore", "Basel", "Bucharest", "Hamburg", "Noida"
    })
    void rejectsRealNonUsBoardFormats(String location) {
        assertThat(UsLocation.isUnitedStates(location)).as(location).isFalse();
    }

    /**
     * Workday writes "11 Locations" when a requisition spans several sites, and that string names
     * no country at all. A hard US-only filter therefore excludes it. This is a deliberate,
     * documented trade: some of those postings really are American, but nothing in the listing
     * says so, and a filter the user asked to be hard should not guess.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2 Locations", "7 Locations", "11 Locations"})
    void excludesWorkdayMultiLocationPlaceholders(String location) {
        assertThat(UsLocation.isUnitedStates(location)).as(location).isFalse();
    }
}
