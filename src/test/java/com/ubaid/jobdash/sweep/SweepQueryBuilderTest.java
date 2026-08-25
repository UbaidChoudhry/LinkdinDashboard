package com.ubaid.jobdash.sweep;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SweepQueryBuilderTest {

    @Test
    void buildsExpectedParamsForA24HourWindow() {
        URI uri = SweepQueryBuilder.buildUri("software engineer", "New York City Metropolitan Area", 24, 0);
        String s = uri.toString();

        assertThat(s).contains("f_TPR=r86400");
        assertThat(s).contains("sortBy=DD");
        assertThat(s).contains("start=0");
        assertThat(s).startsWith("https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?");
    }

    @Test
    void stepsStartByTen() {
        assertThat(SweepQueryBuilder.buildUri("k", "l", 24, 10).toString()).contains("start=10");
        assertThat(SweepQueryBuilder.buildUri("k", "l", 24, 20).toString()).contains("start=20");
        assertThat(SweepQueryBuilder.buildUri("k", "l", 24, 990).toString()).contains("start=990");
    }

    @Test
    void neverEmitsSpellCorrectionOrCurrentJobId() {
        String s = SweepQueryBuilder.buildUri("engineer", "Austin, Texas Metropolitan Area", 24, 0).toString();
        assertThat(s).doesNotContain("spellCorrectionEnabled");
        assertThat(s).doesNotContain("currentJobId");
    }

    @Test
    void neverEncodesABooleanNotClauseIntoKeywords() {
        // Sanity check on the contract: this builder never adds its own boolean operators - it
        // only ever URL-encodes whatever keywords string it's given. Exclusion is applied
        // locally elsewhere (task 6), never appended here as "NOT x".
        String s = SweepQueryBuilder.buildUri("engineer", "New York City Metropolitan Area", 24, 0).toString();
        assertThat(s).doesNotContain("NOT");
    }

    @Test
    void convertsHoursToSecondsForFTpr() {
        assertThat(SweepQueryBuilder.buildUri("k", "l", 1, 0).toString()).contains("f_TPR=r3600");
        assertThat(SweepQueryBuilder.buildUri("k", "l", 168, 0).toString()).contains("f_TPR=r604800");
    }
}
