package com.ubaid.jobdash.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Three independent, composable rate-limiting layers for the LinkedIn sweep:
 * <ul>
 *     <li><b>Layer A — inter-request pacing</b> ({@link #acquirePacing()}): a global
 *     single-permit gate. Every outbound request serializes through it, and the delay
 *     before the next request is measured from the <em>end</em> of the previous one
 *     (recorded when the returned {@link PacingPermit} is closed) — not from when it
 *     started, so a slow response can never shorten the following gap. After LinkedIn blocks
 *     us the delay stretches: it doubles for every block in the last {@code slowdownWindow},
 *     up to {@code maxSlowdown} times the normal delay (see {@link #slowdown()}).</li>
 *     <li><b>Layer B — per-run cap</b> ({@link #checkBudget()}): a simple in-memory
 *     counter reset per run via {@link #resetRunCount()}. Exceeding it is a clean stop
 *     signal, not an error.</li>
 *     <li><b>Layer C — rolling 24h budget</b> ({@link #checkBudget()}): counted through
 *     {@link RequestBudgetStore#countSince(Instant)} so a restart never resets it.</li>
 * </ul>
 * Layers B and C are checked together by {@link #checkBudget()} before a request is
 * attempted; layer A is a separate acquire/release step around the actual HTTP call.
 */
public final class RateLimiter {

    private final Clock clock;
    private final Sleeper sleeper;
    private final RequestBudgetStore budgetStore;
    private final Duration minDelay;
    private final Duration maxDelay;
    private final int perRunCap;
    private final int perRollingDayCap;
    private final Duration slowdownWindow;
    private final int maxSlowdown;

    private final ReentrantLock pacingGate = new ReentrantLock();
    private volatile Instant lastResponseEnd;
    private final AtomicInteger runCount = new AtomicInteger(0);

    /** A limiter that never slows down after a block. */
    public RateLimiter(Clock clock, Sleeper sleeper, RequestBudgetStore budgetStore,
                        Duration minDelay, Duration maxDelay, int perRunCap, int perRollingDayCap) {
        this(clock, sleeper, budgetStore, minDelay, maxDelay, perRunCap, perRollingDayCap, Duration.ZERO, 1);
    }

    public RateLimiter(Clock clock, Sleeper sleeper, RequestBudgetStore budgetStore,
                        Duration minDelay, Duration maxDelay, int perRunCap, int perRollingDayCap,
                        Duration slowdownWindow, int maxSlowdown) {
        if (minDelay.isNegative() || maxDelay.compareTo(minDelay) < 0) {
            throw new IllegalArgumentException("require 0 <= minDelay <= maxDelay");
        }
        if (slowdownWindow.isNegative() || maxSlowdown < 1) {
            throw new IllegalArgumentException("require slowdownWindow >= 0 and maxSlowdown >= 1");
        }
        this.slowdownWindow = slowdownWindow;
        this.maxSlowdown = maxSlowdown;
        this.clock = clock;
        this.sleeper = sleeper;
        this.budgetStore = budgetStore;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.perRunCap = perRunCap;
        this.perRollingDayCap = perRollingDayCap;
    }

    /** Result of a layer B/C check. Distinguishable so callers can report why a request was refused. */
    public sealed interface Decision {
        record Proceed() implements Decision {
        }

        record BlockedByRunCap(int runCap) implements Decision {
        }

        record BlockedByDailyBudget(int currentCount, int cap) implements Decision {
        }
    }

    /** Checks the per-run cap (layer B) and the rolling 24h budget (layer C), in that order. */
    public Decision checkBudget() {
        if (runCount.get() >= perRunCap) {
            return new Decision.BlockedByRunCap(perRunCap);
        }
        int dailyCount = budgetStore.countSince(clock.instant().minus(Duration.ofHours(24)));
        if (dailyCount >= perRollingDayCap) {
            return new Decision.BlockedByDailyBudget(dailyCount, perRollingDayCap);
        }
        return new Decision.Proceed();
    }

    /** Consumes one unit of the per-run cap. Call once a request is actually going out. */
    public void markRequestStarted() {
        runCount.incrementAndGet();
    }

    /** Resets the per-run counter. Call at the start of each sweep run. */
    public void resetRunCount() {
        runCount.set(0);
    }

    public int runCount() {
        return runCount.get();
    }

    /**
     * Blocks (interruptibly) until this caller may send its request, then holds the global
     * pacing gate until the returned permit is {@link PacingPermit#close() closed}. Close it
     * as soon as the response is fully received — that moment becomes the baseline for the
     * next request's delay.
     */
    public PacingPermit acquirePacing() throws InterruptedException {
        pacingGate.lockInterruptibly();
        try {
            Instant now = clock.instant();
            Instant last = lastResponseEnd;
            long waitedMs = 0;
            if (last != null) {
                Duration delay = randomDelay().multipliedBy(slowdown());
                Instant target = last.plus(delay);
                if (target.isAfter(now)) {
                    Duration toWait = Duration.between(now, target);
                    waitedMs = toWait.toMillis();
                    sleeper.sleep(toWait);
                }
            }
            return new PacingPermit(waitedMs);
        } catch (InterruptedException e) {
            pacingGate.unlock();
            throw e;
        } catch (RuntimeException e) {
            pacingGate.unlock();
            throw e;
        }
    }

    /**
     * How many times the normal delay to wait: 2^(blocks LinkedIn sent within the last
     * {@code slowdownWindow}), capped at {@code maxSlowdown}. Once LinkedIn has flagged this IP
     * it stays touchy for hours: on 2026-10-07 a run went 180 requests before its first 429, then
     * each retry at full speed got 15, 22, then 5 through. Read from the request log, so it
     * survives a restart and winds back down by itself as blocks age out of the window.
     */
    int slowdown() {
        if (maxSlowdown <= 1 || slowdownWindow.isZero()) {
            return 1;
        }
        int blocks = budgetStore.countBlockedSince(clock.instant().minus(slowdownWindow));
        if (blocks >= 31) {
            return maxSlowdown;
        }
        return (int) Math.min(maxSlowdown, 1L << blocks);
    }

    private Duration randomDelay() {
        long minMs = minDelay.toMillis();
        long maxMs = maxDelay.toMillis();
        long span = maxMs - minMs;
        long extra = span <= 0 ? 0 : ThreadLocalRandom.current().nextLong(span + 1);
        return Duration.ofMillis(minMs + extra);
    }

    /** Held for the duration of one outbound request; closing it releases the pacing gate. */
    public final class PacingPermit implements AutoCloseable {
        private final long waitedMs;
        private boolean closed = false;

        private PacingPermit(long waitedMs) {
            this.waitedMs = waitedMs;
        }

        /** Milliseconds this caller actually spent waiting for its pacing slot. */
        public long waitedMs() {
            return waitedMs;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            lastResponseEnd = clock.instant();
            pacingGate.unlock();
        }
    }
}
