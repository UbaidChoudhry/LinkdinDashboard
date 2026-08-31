package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.store.ExternalRequestLogRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SalaryRateLimiterTest {

    private static final Duration MIN_DELAY = Duration.ofSeconds(1);

    @TempDir
    Path tempDir;

    private FakeClock clock;
    private FakeSleeper sleeper;
    private ExternalRequestLogRepository log;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(Instant.parse("2026-09-01T12:00:00Z"));
        sleeper = new FakeSleeper(clock);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("salary-rl-test.db"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        log = new ExternalRequestLogRepository(JdbcClient.create(ds));
    }

    private SalaryRateLimiter newLimiter(int adzunaCap) {
        return new SalaryRateLimiter(clock, sleeper, log, MIN_DELAY,
                Map.of("adzuna", adzunaCap, "h1bapi", 20),
                Map.of("adzuna", 0, "h1bapi", 0));
    }

    @Test
    void firstPaceNeverWaitsButSubsequentPaceWaitsTheConfiguredDelay() throws InterruptedException {
        SalaryRateLimiter limiter = newLimiter(200);

        limiter.pace();
        assertThat(sleeper.sleepCount()).isZero();

        limiter.pace();
        assertThat(sleeper.sleeps()).containsExactly(MIN_DELAY);
    }

    @Test
    void paceOnlyWaitsTheRemainderWhenSomeTimeAlreadyElapsed() throws InterruptedException {
        SalaryRateLimiter limiter = newLimiter(200);

        limiter.pace();
        clock.advance(Duration.ofMillis(400));
        limiter.pace();

        assertThat(sleeper.sleeps()).containsExactly(Duration.ofMillis(600));
    }

    @Test
    void dailyCapBlocksAfterNCallsInWindowAndReAllowsAfterItSlides() {
        SalaryRateLimiter limiter = newLimiter(3);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);
            limiter.recordCall("adzuna", "https://api.adzuna.com/x", 200);
        }

        assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.BLOCKED_DAILY_CAP);
        // A different source is unaffected.
        assertThat(limiter.check("h1bapi")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);

        // Slide the 24h window past all three recorded calls.
        clock.advance(Duration.ofHours(24).plusMinutes(1));
        assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);
    }

    /**
     * The reason the monthly window exists: Adzuna's free tier is a monthly allowance, and a
     * daily cap alone cannot protect it — staying under the daily limit every day for a month
     * still blows the month. Without the 30-day check this walks straight past the quota.
     */
    @Test
    void monthlyCapBlocksEvenWhenEveryIndividualDayIsUnderTheDailyCap() {
        SalaryRateLimiter limiter = new SalaryRateLimiter(clock, sleeper, log, MIN_DELAY,
                Map.of("adzuna", 5, "h1bapi", 20),
                Map.of("adzuna", 12, "h1bapi", 0));

        // 4 calls a day (under the daily cap of 5) for 3 days = 12 calls, hitting the monthly cap.
        for (int day = 0; day < 3; day++) {
            for (int i = 0; i < 4; i++) {
                assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);
                limiter.recordCall("adzuna", "https://api.adzuna.com/x", 200);
            }
            clock.advance(Duration.ofHours(24).plusMinutes(1));
        }

        assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.BLOCKED_MONTHLY_CAP);

        // Slide the 30-day window past every recorded call and it opens back up.
        clock.advance(Duration.ofDays(30));
        assertThat(limiter.check("adzuna")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);
    }

    /** A monthly cap of 0 means "no monthly limit" — only the daily cap applies. */
    @Test
    void monthlyCapOfZeroMeansUnlimited() {
        SalaryRateLimiter limiter = new SalaryRateLimiter(clock, sleeper, log, MIN_DELAY,
                Map.of("adzuna", 5, "h1bapi", 20),
                Map.of("adzuna", 0, "h1bapi", 0));

        for (int day = 0; day < 10; day++) {
            for (int i = 0; i < 4; i++) {
                limiter.recordCall("h1bapi", "https://h1bapi.com/x", 200);
            }
            clock.advance(Duration.ofHours(24).plusMinutes(1));
        }

        assertThat(limiter.check("h1bapi")).isEqualTo(SalaryRateLimiter.Decision.ALLOWED);
    }
}
