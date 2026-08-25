package com.ubaid.jobdash.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Trips on hard (429 / 999) or accumulated soft failures, and reopens for LinkedIn's own
 * good with a cooldown that doubles on each consecutive trip.
 * <p>
 * Trip rules (see {@link FailureKind}):
 * <ul>
 *     <li>{@link FailureKind#HARD} — opens immediately, from any state.</li>
 *     <li>{@link FailureKind#SOFT} — increments a soft-failure counter while CLOSED;
 *     opens once the counter reaches the configured threshold. Any success resets the
 *     counter to zero. While HALF_OPEN, a single soft failure fails the probe and opens
 *     immediately (a probe gets exactly one chance).</li>
 * </ul>
 * Cooldown starts at {@code openDuration} and doubles on each consecutive trip (a trip
 * that happens before a probe ever succeeds), capped at {@code maxOpenDuration}. Once
 * the cooldown expires, the next {@link #tryAcquire()} call transitions to HALF_OPEN and
 * admits exactly one probe; concurrent callers during that window are refused, never
 * queued. State is read/written through {@link CircuitStateStore} so it survives a restart.
 */
public final class CircuitBreaker {

    public enum Admission {ADMITTED, REFUSED}

    public enum FailureKind {HARD, SOFT}

    private final CircuitStateStore store;
    private final Clock clock;
    private final Duration openDuration;
    private final Duration maxOpenDuration;
    private final int softFailureThreshold;

    // Guards read-modify-write of the persisted snapshot so concurrent callers in this
    // process never race each other's load()/save() pair.
    private final ReentrantLock lock = new ReentrantLock();
    // In-memory only: tracks whether the single admitted half-open probe is still outstanding.
    // Deliberately not persisted — a process restart mid-probe simply lets the next caller
    // retry admission, which is safe since the breaker is still OPEN or HALF_OPEN either way.
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    public CircuitBreaker(CircuitStateStore store, Clock clock, Duration openDuration,
                           Duration maxOpenDuration, int softFailureThreshold) {
        this.store = store;
        this.clock = clock;
        this.openDuration = openDuration;
        this.maxOpenDuration = maxOpenDuration;
        this.softFailureThreshold = softFailureThreshold;
    }

    /**
     * Decides whether a request may proceed. CLOSED always admits. OPEN refuses until the
     * cooldown expires, at which point exactly one caller is transitioned to HALF_OPEN and
     * admitted as the probe; all others are refused. HALF_OPEN admits at most one concurrent
     * probe.
     */
    public Admission tryAcquire() {
        lock.lock();
        try {
            CircuitSnapshot snap = store.load();
            Instant now = clock.instant();
            return switch (snap.state()) {
                case CLOSED -> Admission.ADMITTED;
                case OPEN -> {
                    if (snap.openUntil() != null && !now.isBefore(snap.openUntil())) {
                        if (probeInFlight.compareAndSet(false, true)) {
                            store.save(new CircuitSnapshot(CircuitState.HALF_OPEN, snap.consecutiveTrips(),
                                    0, snap.openedAt(), snap.openUntil()));
                            yield Admission.ADMITTED;
                        }
                        yield Admission.REFUSED;
                    }
                    yield Admission.REFUSED;
                }
                case HALF_OPEN -> {
                    if (probeInFlight.compareAndSet(false, true)) {
                        yield Admission.ADMITTED;
                    }
                    yield Admission.REFUSED;
                }
            };
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reports a successful request. Always resets the soft-failure counter to zero. If this
     * was the outstanding half-open probe, fully closes the breaker and resets the consecutive
     * trip count (so the next trip's cooldown starts back at {@code openDuration}).
     */
    public void recordSuccess() {
        lock.lock();
        try {
            CircuitSnapshot snap = store.load();
            if (snap.state() == CircuitState.HALF_OPEN) {
                store.save(new CircuitSnapshot(CircuitState.CLOSED, 0, 0, null, null));
            } else if (snap.softFailureCount() != 0) {
                store.save(new CircuitSnapshot(snap.state(), snap.consecutiveTrips(), 0,
                        snap.openedAt(), snap.openUntil()));
            }
        } finally {
            probeInFlight.set(false);
            lock.unlock();
        }
    }

    /**
     * Reports a failed request. A {@link FailureKind#HARD} failure, or any failure while
     * HALF_OPEN (the probe failed), opens the breaker immediately with a doubled cooldown.
     * A {@link FailureKind#SOFT} failure while CLOSED only opens once the soft-failure
     * counter reaches the configured threshold.
     */
    public void recordFailure(FailureKind kind) {
        lock.lock();
        try {
            CircuitSnapshot snap = store.load();
            if (snap.state() == CircuitState.HALF_OPEN) {
                trip(snap);
                return;
            }
            if (kind == FailureKind.HARD) {
                trip(snap);
                return;
            }
            int softCount = snap.softFailureCount() + 1;
            if (softCount >= softFailureThreshold) {
                trip(snap);
            } else {
                store.save(new CircuitSnapshot(snap.state(), snap.consecutiveTrips(), softCount,
                        snap.openedAt(), snap.openUntil()));
            }
        } finally {
            probeInFlight.set(false);
            lock.unlock();
        }
    }

    private void trip(CircuitSnapshot snap) {
        int newTrips = snap.consecutiveTrips() + 1;
        Duration cooldown = cooldownFor(snap.consecutiveTrips());
        Instant now = clock.instant();
        store.save(new CircuitSnapshot(CircuitState.OPEN, newTrips, 0, now, now.plus(cooldown)));
    }

    /** Cooldown for the trip about to happen, given how many consecutive trips preceded it. */
    private Duration cooldownFor(int previousTrips) {
        Duration base = openDuration.compareTo(maxOpenDuration) > 0 ? maxOpenDuration : openDuration;
        if (previousTrips <= 0) {
            return base;
        }
        long baseMs = base.toMillis();
        long capMs = maxOpenDuration.toMillis();
        int shift = Math.min(previousTrips, 62); // guard against overflow on pathological configs
        long ms = baseMs << shift;
        if (ms <= 0 || ms > capMs) {
            ms = capMs;
        }
        return Duration.ofMillis(ms);
    }
}
