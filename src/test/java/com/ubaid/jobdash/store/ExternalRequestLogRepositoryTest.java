package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalRequestLogRepositoryTest extends AbstractStoreTest {

    @Test
    void countSinceIsPerSourceAndRespectsTheBoundary() {
        Instant base = Instant.parse("2026-09-01T12:00:00Z");

        externalRequestLogRepository.record("adzuna", base.minus(1, ChronoUnit.HOURS), "https://a/1", 200);
        externalRequestLogRepository.record("adzuna", base, "https://a/2", 200);
        externalRequestLogRepository.record("adzuna", base.plus(1, ChronoUnit.HOURS), "https://a/3", 429);
        externalRequestLogRepository.record("h1bapi", base, "https://h/1", 200);

        assertThat(externalRequestLogRepository.countSince("adzuna", base)).isEqualTo(2);
        assertThat(externalRequestLogRepository.countSince("adzuna", base.minus(2, ChronoUnit.HOURS))).isEqualTo(3);
        assertThat(externalRequestLogRepository.countSince("adzuna", base.plus(2, ChronoUnit.HOURS))).isEqualTo(0);
        assertThat(externalRequestLogRepository.countSince("h1bapi", base)).isEqualTo(1);
    }
}
