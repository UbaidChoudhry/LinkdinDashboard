package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.http.Sleeper;
import com.ubaid.jobdash.store.ExternalRequestLogRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two rate-limiting layers for the external salary APIs:
 * <ul>
 *     <li><b>Pacing</b> ({@link #pace()}): a single in-process gate that spaces successive
 *     outbound calls by at least {@code minDelay}, measured from the previous call through this
 *     limiter. Modelled on {@code RateLimiter}'s pacing gate but simpler — there is no per-run
 *     cap and the delay is fixed, not randomized.</li>
 *     <li><b>Rolling daily cap</b> ({@link #check(String)}): compares the number of calls to a
 *     source in the last 24h (from {@link ExternalRequestLogRepository#countSince}) against that
 *     source's configured cap, so a restart never resets it.</li>
 * </ul>
 * Must be used as a singleton — the pacing gate ({@code lastCallAt}) is an instance field.
 */
public final class SalaryRateLimiter {

    private final Clock clock;
    private final Sleeper sleeper;
    private final ExternalRequestLogRepository log;
    private final Duration minDelay;
    private final Map<String, Integer> dailyCaps;

    private final ReentrantLock pacingGate = new ReentrantLock();
    private volatile Instant lastCallAt;

    public SalaryRateLimiter(Clock clock, Sleeper sleeper, ExternalRequestLogRepository log,
                             Duration minDelay, Map<String, Integer> dailyCaps) {
        if (minDelay.isNegative()) {
            throw new IllegalArgumentException("minDelay must not be negative");
        }
        this.clock = clock;
        this.sleeper = sleeper;
        this.log = log;
        this.minDelay = minDelay;
        this.dailyCaps = Map.copyOf(dailyCaps);
    }

    /** Outcome of a daily-cap check for one source. */
    public enum Decision {
        ALLOWED,
        BLOCKED_DAILY_CAP
    }

    /**
     * Checks the rolling 24h cap for {@code source}. Returns {@link Decision#BLOCKED_DAILY_CAP}
     * once the count of calls in the last 24h reaches the configured cap.
     */
    public Decision check(String source) {
        int cap = dailyCaps.getOrDefault(source, 0);
        Instant since = clock.instant().minus(Duration.ofHours(24));
        long used = log.countSince(source, since);
        return used >= cap ? Decision.BLOCKED_DAILY_CAP : Decision.ALLOWED;
    }

    /**
     * Blocks (interruptibly) until at least {@code minDelay} has elapsed since the previous call
     * through this limiter, then records "now" as the new baseline.
     */
    public void pace() throws InterruptedException {
        pacingGate.lockInterruptibly();
        try {
            Instant now = clock.instant();
            if (lastCallAt != null) {
                Instant target = lastCallAt.plus(minDelay);
                if (target.isAfter(now)) {
                    sleeper.sleep(Duration.between(now, target));
                }
            }
            lastCallAt = clock.instant();
        } finally {
            pacingGate.unlock();
        }
    }

    /** Records an outbound call so it counts against the source's rolling daily cap. */
    public void recordCall(String source, String url, Integer statusCode) {
        log.record(source, clock.instant(), url, statusCode);
    }
}
