package com.ubaid.jobdash.salary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyKeyTest {

    @Test
    void stripsTrailingLegalSuffixAndRegionQualifier() {
        assertThat(CompanyKey.of("COGNIZANT TECHNOLOGY SOLUTIONS US CORP"))
                .isEqualTo("cognizant technology solutions");
    }

    @Test
    void stripsSingleSuffix() {
        assertThat(CompanyKey.of("Google LLC")).isEqualTo("google");
    }

    @Test
    void stripsAmpersandAndDottedUsSuffix() {
        assertThat(CompanyKey.of("Ernst & Young U.S. LLP")).isEqualTo("ernst young");
    }

    @Test
    void handlesDottedInc() {
        assertThat(CompanyKey.of("Palantir Technologies Inc.")).isEqualTo("palantir technologies");
    }

    @Test
    void collapsesWhitespaceAndLowercases() {
        assertThat(CompanyKey.of("  Acme   Robotics  ")).isEqualTo("acme robotics");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(CompanyKey.of(null)).isEmpty();
        assertThat(CompanyKey.of("   ")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNameIsOnlyASuffix() {
        assertThat(CompanyKey.of("LLC")).isEmpty();
    }
}
