package com.ubaid.jobdash.http;

import com.ubaid.jobdash.http.support.FakeClock;
import com.ubaid.jobdash.http.support.InMemoryCircuitStateStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private static final Duration OPEN_DURATION = Duration.ofMinutes(30);
    private static final Duration MAX_OPEN_DURATION = Duration.ofMinutes(60);
    private static final int SOFT_THRESHOLD = 2;

    private CircuitBreaker newBreaker(FakeClock clock, InMemoryCircuitStateStore store) {
        return new CircuitBreaker(store, clock, OPEN_DURATION, MAX_OPEN_DURATION, SOFT_THRESHOLD);
    }

    @Test
    void startsClosedAndAdmits() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        CircuitBreaker breaker = newBreaker(clock, new InMemoryCircuitStateStore());
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
    }

    @Test
    void http429OpensImmediatelyOnFirstOccurrence() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);

        assertEquals(CircuitState.OPEN, store.load().state());
        assertEquals(1, store.load().consecutiveTrips());
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
    }

    @Test
    void http999OpensImmediatelyOnFirstOccurrence() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD); // 999 mapped to HARD by the caller

        assertEquals(CircuitState.OPEN, store.load().state());
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
    }

    @Test
    void singleSoftFailureDoesNotOpen() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);

        assertEquals(CircuitState.CLOSED, store.load().state());
        assertEquals(1, store.load().softFailureCount());
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
    }

    @Test
    void twoConsecutiveSoftFailuresOpen() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);

        assertEquals(CircuitState.OPEN, store.load().state());
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
    }

    @Test
    void successBetweenSoftFailuresResetsTheCounter() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
        assertEquals(1, store.load().softFailureCount());

        breaker.tryAcquire();
        breaker.recordSuccess();
        assertEquals(0, store.load().softFailureCount());

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);
        assertEquals(1, store.load().softFailureCount());
        assertEquals(CircuitState.CLOSED, store.load().state());
    }

    @Test
    void cooldownDoublesAcrossConsecutiveTripsAndCapsAtMax() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        // Trip 1: CLOSED -> OPEN.
        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        CircuitSnapshot afterTrip1 = store.load();
        assertEquals(1, afterTrip1.consecutiveTrips());
        assertEquals(OPEN_DURATION, Duration.between(afterTrip1.openedAt(), afterTrip1.openUntil()));

        // Cooldown expires; probe admitted; probe fails -> trip 2, cooldown doubles.
        clock.advance(OPEN_DURATION.plusSeconds(1));
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
        assertEquals(CircuitState.HALF_OPEN, store.load().state());
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        CircuitSnapshot afterTrip2 = store.load();
        assertEquals(2, afterTrip2.consecutiveTrips());
        assertEquals(MAX_OPEN_DURATION, Duration.between(afterTrip2.openedAt(), afterTrip2.openUntil()),
                "30m doubled is exactly 60m, the cap");

        // Cooldown expires again; probe admitted; probe fails -> trip 3, would be 120m but caps at 60m.
        clock.advance(MAX_OPEN_DURATION.plusSeconds(1));
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        CircuitSnapshot afterTrip3 = store.load();
        assertEquals(3, afterTrip3.consecutiveTrips());
        assertEquals(MAX_OPEN_DURATION, Duration.between(afterTrip3.openedAt(), afterTrip3.openUntil()),
                "cooldown must be capped at maxOpenDuration");
    }

    @Test
    void refusesBeforeCooldownExpires() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);

        clock.advance(OPEN_DURATION.minusSeconds(1));
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
        assertEquals(CircuitState.OPEN, store.load().state(), "must not transition early");
    }

    @Test
    void halfOpenAdmitsExactlyOneProbeConcurrentCallerRefused() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        clock.advance(OPEN_DURATION.plusSeconds(1));

        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire(), "first caller becomes the probe");
        assertEquals(CircuitState.HALF_OPEN, store.load().state());

        // A second, concurrent caller must be refused outright - not queued for a future turn.
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
        assertEquals(CircuitBreaker.Admission.REFUSED, breaker.tryAcquire());
    }

    @Test
    void probeSuccessClosesAndResetsBackoff() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        clock.advance(OPEN_DURATION.plusSeconds(1));
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());

        breaker.recordSuccess();

        CircuitSnapshot snap = store.load();
        assertEquals(CircuitState.CLOSED, snap.state());
        assertEquals(0, snap.consecutiveTrips());
        assertEquals(0, snap.softFailureCount());

        // A fresh probe slot must be available again after closing (probeInFlight released).
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
    }

    @Test
    void probeFailureReopensWithDoubledCooldownAndReleasesProbeSlot() {
        FakeClock clock = new FakeClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();
        CircuitBreaker breaker = newBreaker(clock, store);

        breaker.tryAcquire();
        breaker.recordFailure(CircuitBreaker.FailureKind.HARD);
        clock.advance(OPEN_DURATION.plusSeconds(1));
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());

        // Even a SOFT failure during the probe must reopen immediately - a probe gets one shot.
        breaker.recordFailure(CircuitBreaker.FailureKind.SOFT);

        CircuitSnapshot snap = store.load();
        assertEquals(CircuitState.OPEN, snap.state());
        assertEquals(2, snap.consecutiveTrips());
        assertEquals(MAX_OPEN_DURATION, Duration.between(snap.openedAt(), snap.openUntil()));
        assertTrue(store.load().state() == CircuitState.OPEN);

        // Probe slot must have been released too, not left stuck.
        clock.advance(MAX_OPEN_DURATION.plusSeconds(1));
        assertEquals(CircuitBreaker.Admission.ADMITTED, breaker.tryAcquire());
    }
}
