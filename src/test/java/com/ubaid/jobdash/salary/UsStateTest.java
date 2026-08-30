package com.ubaid.jobdash.salary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsStateTest {

    @Test
    void parsesFullStateNameFromCommaSeparatedLocation() {
        assertThat(UsState.fromLocation("Austin, Texas, United States")).isEqualTo("TX");
    }

    @Test
    void parsesTwoLetterCode() {
        assertThat(UsState.fromLocation("New York, NY")).isEqualTo("NY");
    }

    @Test
    void resolvesKnownMetroPhrases() {
        assertThat(UsState.fromLocation("Greater Seattle Area")).isEqualTo("WA");
        assertThat(UsState.fromLocation("San Francisco Bay Area")).isEqualTo("CA");
        assertThat(UsState.fromLocation("New York City Metropolitan Area")).isEqualTo("NY");
    }

    @Test
    void returnsEmptyForNationwideRemoteOrUnknown() {
        assertThat(UsState.fromLocation("United States")).isEmpty();
        assertThat(UsState.fromLocation("Remote")).isEmpty();
        assertThat(UsState.fromLocation("")).isEmpty();
        assertThat(UsState.fromLocation(null)).isEmpty();
        assertThat(UsState.fromLocation("London, United Kingdom")).isEmpty();
    }

    @Test
    void multiWordStateNameWithoutCommaBoundary() {
        assertThat(UsState.fromLocation("Greater New York City Area")).isEqualTo("NY");
    }
}
