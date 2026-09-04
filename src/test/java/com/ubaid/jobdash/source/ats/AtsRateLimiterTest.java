package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.store.AbstractStoreTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AtsRateLimiterTest extends AbstractStoreTest {

    private FakeClock clock;
    private FakeSleeper sleeper;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-09-07T00:00:00Z"));
        sleeper = new FakeSleeper(clock);
    }

    private AtsRateLimiter limiter(Duration minDelay, Map<String, Integer> dailyCaps) {
        return new AtsRateLimiter(clock, sleeper, externalRequestLogRepository, minDelay, dailyCaps);
    }

    @Test
    void dailyCapBlocksOnceExceeded() {
        AtsRateLimiter limiter = limiter(Duration.ZERO, Map.of("greenhouse", 2));
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.ALLOWED);

        limiter.recordCall("greenhouse", "http://example.com/1", 200);
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.ALLOWED);

        limiter.recordCall("greenhouse", "http://example.com/2", 200);
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.BLOCKED_DAILY_CAP);
    }

    @Test
    void dailyCapIsPerSourceNotShared() {
        AtsRateLimiter limiter = limiter(Duration.ZERO, Map.of("greenhouse", 1, "lever", 5));
        limiter.recordCall("greenhouse", "http://example.com/1", 200);
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.BLOCKED_DAILY_CAP);
        assertThat(limiter.check("lever")).isEqualTo(AtsRateLimiter.Decision.ALLOWED);
    }

    @Test
    void cappedRequestsOlderThan24hRollOffTheWindow() {
        AtsRateLimiter limiter = limiter(Duration.ZERO, Map.of("greenhouse", 1));
        limiter.recordCall("greenhouse", "http://example.com/1", 200);
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.BLOCKED_DAILY_CAP);

        clock.advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(limiter.check("greenhouse")).isEqualTo(AtsRateLimiter.Decision.ALLOWED);
    }

    @Test
    void paceWaitsUntilMinDelayHasElapsedSinceThePreviousCall() throws InterruptedException {
        AtsRateLimiter limiter = limiter(Duration.ofMillis(250), Map.of());
        limiter.pace();
        assertThat(sleeper.sleepCount()).isZero();

        limiter.pace();
        assertThat(sleeper.sleepCount()).isEqualTo(1);
        assertThat(sleeper.sleeps().get(0)).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void recordCallWritesToExternalRequestLogNotRequestLog() {
        AtsRateLimiter limiter = limiter(Duration.ZERO, Map.of("workday", 10));
        limiter.recordCall("workday", "http://example.com/jobs", 200);

        assertThat(externalRequestLogRepository.countSince("workday", Instant.EPOCH)).isEqualTo(1);
        // request_log is LinkedIn's separate budget table; AtsRateLimiter must never touch it.
        Long requestLogCount = client.sql("select count(*) from request_log").query(Long.class).single();
        assertThat(requestLogCount).isZero();
    }
}
