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
 *     <li><b>Rolling monthly cap</b> (same method, 30-day window): several of these providers
 *     bill a <em>monthly</em> free allowance, which a daily cap alone cannot protect — 30 days
 *     at a "safe" daily rate can still blow a monthly quota. A cap of {@code <= 0} means the
 *     source has no monthly limit.</li>
 * </ul>
 * Must be used as a singleton — the pacing gate ({@code lastCallAt}) is an instance field.
 */
public final class SalaryRateLimiter {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration MONTH = Duration.ofDays(30);

    private final Clock clock;
    private final Sleeper sleeper;
    private final ExternalRequestLogRepository log;
    private final Duration minDelay;
    private final Map<String, Integer> dailyCaps;
    private final Map<String, Integer> monthlyCaps;

    private final ReentrantLock pacingGate = new ReentrantLock();
    private volatile Instant lastCallAt;

    public SalaryRateLimiter(Clock clock, Sleeper sleeper, ExternalRequestLogRepository log,
                             Duration minDelay, Map<String, Integer> dailyCaps,
                             Map<String, Integer> monthlyCaps) {
        if (minDelay.isNegative()) {
            throw new IllegalArgumentException("minDelay must not be negative");
        }
        this.clock = clock;
        this.sleeper = sleeper;
        this.log = log;
        this.minDelay = minDelay;
        this.dailyCaps = Map.copyOf(dailyCaps);
        this.monthlyCaps = Map.copyOf(monthlyCaps);
    }

    /**
     * Outcome of a quota check for one source. Callers should treat anything other than
     * {@link #ALLOWED} as "skip this source" rather than switching on each blocked reason.
     */
    public enum Decision {
        ALLOWED,
        BLOCKED_DAILY_CAP,
        BLOCKED_MONTHLY_CAP
    }

    /**
     * Checks both rolling windows for {@code source}: the 24h cap first, then the 30-day cap
     * (skipped when that source's monthly cap is {@code <= 0}, meaning "no monthly limit").
     */
    public Decision check(String source) {
        Instant now = clock.instant();

        int dailyCap = dailyCaps.getOrDefault(source, 0);
        if (log.countSince(source, now.minus(DAY)) >= dailyCap) {
            return Decision.BLOCKED_DAILY_CAP;
        }

        int monthlyCap = monthlyCaps.getOrDefault(source, 0);
        if (monthlyCap > 0 && log.countSince(source, now.minus(MONTH)) >= monthlyCap) {
            return Decision.BLOCKED_MONTHLY_CAP;
        }

        return Decision.ALLOWED;
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
