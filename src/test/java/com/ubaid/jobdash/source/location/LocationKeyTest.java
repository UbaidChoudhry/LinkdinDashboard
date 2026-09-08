package com.ubaid.jobdash.source.location;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The cache key must collapse only differences that cannot change which country a string names. */
class LocationKeyTest {

    @Test
    void collapsesCaseAndWhitespaceSoOneSpellingIsClassifiedOnce() {
        assertThat(LocationKey.of("San Francisco Bay Area "))
                .isEqualTo(LocationKey.of("san francisco  bay area"));
    }

    @Test
    void keepsPunctuationBecauseSeparatorsCarryMeaningHere() {
        // Real board formats: "USA.VA.Reston", "US, Remote". Stripping punctuation would merge
        // strings whose separators are the only thing distinguishing them.
        assertThat(LocationKey.of("USA.VA.Reston")).isEqualTo("usa.va.reston");
        assertThat(LocationKey.of("US, Remote")).isEqualTo("us, remote");
        assertThat(LocationKey.of("US, Remote")).isNotEqualTo(LocationKey.of("US Remote"));
    }

    @Test
    void nullAndBlankCollapseToAnEmptyKey() {
        assertThat(LocationKey.of(null)).isEmpty();
        assertThat(LocationKey.of("   ")).isEmpty();
    }
}
