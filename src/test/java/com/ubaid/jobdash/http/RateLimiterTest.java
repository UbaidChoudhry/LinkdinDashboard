package com.ubaid.jobdash.http;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.FakeSleeper;
import com.ubaid.jobdash.http.support.InMemoryRequestBudgetStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final Duration MIN_DELAY = Duration.ofSeconds(6);
    private static final Duration MAX_DELAY = Duration.ofSeconds(12);

    private RateLimiter newLimiter(FakeClock clock, FakeSleeper sleeper, InMemoryRequestBudgetStore store,
                                    int perRunCap, int perRollingDayCap) {
        return new RateLimiter(clock, sleeper, store, MIN_DELAY, MAX_DELAY, perRunCap, perRollingDayCap);
    }

    // --- Layer A: pacing --------------------------------------------------

    @Test
    void firstRequestNeverWaits() throws InterruptedException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 150, 300);

        RateLimiter.PacingPermit permit = limiter.acquirePacing();
        permit.close();

        assertEquals(0, sleeper.sleepCount());
        assertEquals(0L, permit.waitedMs());
    }

    @Test
    void pacingDelayAlwaysLandsWithinConfiguredBounds() throws InterruptedException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 150, 300);

        for (int i = 0; i < 200; i++) {
            RateLimiter.PacingPermit permit = limiter.acquirePacing();
            permit.close();
        }

        // First acquire never waits (no prior response yet); the other 199 must each wait
        // somewhere in [minDelay, maxDelay].
        assertEquals(199, sleeper.sleepCount());
        for (Duration d : sleeper.sleeps()) {
            assertTrue(d.compareTo(MIN_DELAY) >= 0, "delay " + d + " below min");
            assertTrue(d.compareTo(MAX_DELAY) <= 0, "delay " + d + " above max");
        }
    }

    @Test
    void delayIsMeasuredFromEndOfPreviousResponseNotItsStart() throws InterruptedException {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 150, 300);

        RateLimiter.PacingPermit first = limiter.acquirePacing();
        // Simulate a very slow response: 20s elapse while "handling" the request, well past maxDelay.
        clock.advance(Duration.ofSeconds(20));
        first.close(); // response ends now

        Instant responseEndInstant = clock.instant();

        RateLimiter.PacingPermit second = limiter.acquirePacing();
        second.close();

        // The gap between response end and the next request being let through must still be
        // a full [min,max] delay - the 20s the slow response took must not have "paid down"
        // any of the required gap.
        assertEquals(1, sleeper.sleepCount());
        Duration observedGap = Duration.between(responseEndInstant, clock.instant());
        assertTrue(observedGap.compareTo(MIN_DELAY) >= 0,
                "gap " + observedGap + " shorter than min delay despite slow prior response");
        assertTrue(observedGap.compareTo(MAX_DELAY) <= 0, "gap " + observedGap + " exceeds max delay");
    }

    @Test
    void pacingWaitIsInterruptible() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 150, 300);

        assertDoesNotThrowInterrupted(() -> limiter.acquirePacing().close());

        sleeper.interruptNextSleep();
        assertThrowsInterrupted(limiter::acquirePacing);
    }

    private void assertDoesNotThrowInterrupted(ThrowingRunnable r) {
        try {
            r.run();
        } catch (InterruptedException e) {
            throw new AssertionError("unexpected interrupt", e);
        }
    }

    private void assertThrowsInterrupted(ThrowingRunnable r) {
        try {
            r.run();
            throw new AssertionError("expected InterruptedException");
        } catch (InterruptedException expected) {
            // ok
        }
    }

    private interface ThrowingRunnable {
        void run() throws InterruptedException;
    }

    // --- Layer B: per-run cap ----------------------------------------------

    @Test
    void perRunCapRefusesOnceReached() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 3, 300);

        for (int i = 0; i < 3; i++) {
            assertInstanceOf(RateLimiter.Decision.Proceed.class, limiter.checkBudget());
            limiter.markRequestStarted();
        }

        RateLimiter.Decision decision = limiter.checkBudget();
        assertInstanceOf(RateLimiter.Decision.BlockedByRunCap.class, decision);
        assertEquals(3, ((RateLimiter.Decision.BlockedByRunCap) decision).runCap());
    }

    @Test
    void resetRunCountAllowsFreshRun() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        RateLimiter limiter = newLimiter(clock, sleeper, new InMemoryRequestBudgetStore(), 1, 300);

        limiter.markRequestStarted();
        assertInstanceOf(RateLimiter.Decision.BlockedByRunCap.class, limiter.checkBudget());

        limiter.resetRunCount();
        assertInstanceOf(RateLimiter.Decision.Proceed.class, limiter.checkBudget());
    }

    // --- Layer C: rolling 24h budget ----------------------------------------

    @Test
    void dailyBudgetRefusesOnceReached() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T12:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        InMemoryRequestBudgetStore store = new InMemoryRequestBudgetStore();
        RateLimiter limiter = newLimiter(clock, sleeper, store, 150, 5);

        for (int i = 0; i < 5; i++) {
            store.record(new RequestRecord(clock.instant(), "https://example.com", 200, 0, ResponseOutcome.OK));
        }

        RateLimiter.Decision decision = limiter.checkBudget();
        assertInstanceOf(RateLimiter.Decision.BlockedByDailyBudget.class, decision);
        RateLimiter.Decision.BlockedByDailyBudget blocked = (RateLimiter.Decision.BlockedByDailyBudget) decision;
        assertEquals(5, blocked.currentCount());
        assertEquals(5, blocked.cap());
    }

    @Test
    void dailyBudgetIgnoresRequestsOlderThan24Hours() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T12:00:00Z"));
        FakeSleeper sleeper = new FakeSleeper(clock);
        InMemoryRequestBudgetStore store = new InMemoryRequestBudgetStore();
        RateLimiter limiter = newLimiter(clock, sleeper, store, 150, 5);

        // 5 requests just over 24h ago should not count against today's budget.
        Instant old = clock.instant().minus(Duration.ofHours(25));
        for (int i = 0; i < 5; i++) {
            store.record(new RequestRecord(old, "https://example.com", 200, 0, ResponseOutcome.OK));
        }

        assertInstanceOf(RateLimiter.Decision.Proceed.class, limiter.checkBudget());
    }

    @Test
    void dailyBudgetIsReadThroughStoreSoRestartDoesNotResetIt() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T12:00:00Z"));
        InMemoryRequestBudgetStore sharedStore = new InMemoryRequestBudgetStore();

        RateLimiter beforeRestart = newLimiter(clock, new FakeSleeper(clock), sharedStore, 150, 3);
        for (int i = 0; i < 3; i++) {
            sharedStore.record(new RequestRecord(clock.instant(), "https://example.com", 200, 0, ResponseOutcome.OK));
        }
        assertInstanceOf(RateLimiter.Decision.BlockedByDailyBudget.class, beforeRestart.checkBudget());

        // Simulate an app restart: brand-new RateLimiter instance, same backing store.
        RateLimiter afterRestart = newLimiter(clock, new FakeSleeper(clock), sharedStore, 150, 3);
        assertInstanceOf(RateLimiter.Decision.BlockedByDailyBudget.class, afterRestart.checkBudget());
    }
}
