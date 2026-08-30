package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryEstimateRepositoryTest extends AbstractStoreTest {

    private static SalaryEstimate estimate(String source, Double min, Double max, Instant fetchedAt) {
        return new SalaryEstimate("acme robotics", "software engineer", min, max, "USD",
                source, "2025-06-01", 42, fetchedAt);
    }

    @Test
    void upsertThenFindRoundTrips() {
        Instant fetchedAt = Instant.parse("2026-09-01T00:00:00Z");
        salaryEstimateRepository.upsert(estimate("lca", 150000.0, 190000.0, fetchedAt));

        SalaryEstimate found = salaryEstimateRepository.find("acme robotics", "software engineer").orElseThrow();
        assertThat(found.salaryMin()).isEqualTo(150000.0);
        assertThat(found.salaryMax()).isEqualTo(190000.0);
        assertThat(found.source()).isEqualTo("lca");
        assertThat(found.sampleCount()).isEqualTo(42);
        assertThat(found.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    void upsertReplacesAllNonKeyColumns() {
        salaryEstimateRepository.upsert(estimate("adzuna", 100000.0, 120000.0, Instant.parse("2026-01-01T00:00:00Z")));
        salaryEstimateRepository.upsert(estimate("none", null, null, Instant.parse("2026-08-01T00:00:00Z")));

        SalaryEstimate found = salaryEstimateRepository.find("acme robotics", "software engineer").orElseThrow();
        assertThat(found.source()).isEqualTo("none");
        assertThat(found.salaryMin()).isNull();
        assertThat(found.salaryMax()).isNull();
        assertThat(salaryEstimateRepository.find("acme robotics", "software engineer")).isPresent();
    }

    @Test
    void findMissingReturnsEmpty() {
        assertThat(salaryEstimateRepository.find("nobody", "nothing")).isEmpty();
    }

    @Test
    void isFreshWhileWithinTtlAndExpiredAfter() {
        Instant fetchedAt = Instant.parse("2026-06-01T00:00:00Z");
        SalaryEstimate e = estimate("lca", 150000.0, 190000.0, fetchedAt);
        Duration ttl = Duration.ofDays(90);

        assertThat(salaryEstimateRepository.isFresh(e, fetchedAt.plus(Duration.ofDays(30)), ttl)).isTrue();
        assertThat(salaryEstimateRepository.isFresh(e, fetchedAt.plus(Duration.ofDays(89)), ttl)).isTrue();
        assertThat(salaryEstimateRepository.isFresh(e, fetchedAt.plus(Duration.ofDays(91)), ttl)).isFalse();
    }
}
