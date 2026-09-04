package com.ubaid.jobdash.source.ats;

import com.ubaid.jobdash.http.Sleeper;
import com.ubaid.jobdash.store.ExternalRequestLogRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two rate-limiting layers for the ATS job-board APIs (Greenhouse, Lever, Workday), modelled
 * closely on {@code salary.SalaryRateLimiter}:
 * <ul>
 *     <li><b>Pacing</b> ({@link #pace()}): a single in-process gate spacing successive outbound
 *     calls by at least {@code minDelay}.</li>
 *     <li><b>Rolling 24h cap</b> ({@link #check(String)}): compares the number of calls to a
 *     source in the last 24h against that source's configured daily cap.</li>
 * </ul>
 * Records into {@code external_request_log} under source names {@code greenhouse}/{@code lever}/
 * {@code workday}. This is a completely separate budget from LinkedIn's {@code request_log} /
 * {@code http.RateLimiter} — never conflate them (HANDOFF.md §3, "clearing job data never
 * touches request_log").
 * <p>
 * Must be used as a singleton — the pacing gate ({@code lastCallAt}) is an instance field.
 */
public final class AtsRateLimiter {

    private static final Duration DAY = Duration.ofHours(24);

    private final Clock clock;
    private final Sleeper sleeper;
    private final ExternalRequestLogRepository log;
    private final Duration minDelay;
    private final Map<String, Integer> dailyCaps;

    private final ReentrantLock pacingGate = new ReentrantLock();
    private volatile Instant lastCallAt;

    public AtsRateLimiter(Clock clock, Sleeper sleeper, ExternalRequestLogRepository log,
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

    /** Outcome of a quota check for one source. */
    public enum Decision {
        ALLOWED,
        BLOCKED_DAILY_CAP
    }

    /** Checks the rolling 24h cap for {@code source}. */
    public Decision check(String source) {
        int dailyCap = dailyCaps.getOrDefault(source, 0);
        Instant now = clock.instant();
        if (log.countSince(source, now.minus(DAY)) >= dailyCap) {
            return Decision.BLOCKED_DAILY_CAP;
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
