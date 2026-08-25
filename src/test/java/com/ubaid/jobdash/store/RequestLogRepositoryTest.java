package com.ubaid.jobdash.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogRepositoryTest extends AbstractStoreTest {

    @Test
    void countSinceReturnsOnlyRequestsAtOrAfterTheBoundary() {
        Instant base = Instant.parse("2026-08-28T12:00:00Z");

        requestLogRepository.append(null, base.minus(2, ChronoUnit.HOURS), "https://x/1", 200, "ok", 100, 5);
        requestLogRepository.append(null, base.minus(1, ChronoUnit.HOURS), "https://x/2", 200, "ok", 100, 5);
        requestLogRepository.append(null, base, "https://x/3", 200, "ok", 100, 5);
        requestLogRepository.append(null, base.plus(1, ChronoUnit.HOURS), "https://x/4", 200, "ok", 100, 5);
        requestLogRepository.append(null, base.plus(2, ChronoUnit.HOURS), "https://x/5", 429, "rate_limited", 0, 5000);

        assertThat(requestLogRepository.countSince(base)).isEqualTo(3);
        assertThat(requestLogRepository.countSince(base.minus(3, ChronoUnit.HOURS))).isEqualTo(5);
        assertThat(requestLogRepository.countSince(base.plus(3, ChronoUnit.HOURS))).isEqualTo(0);
    }
}
