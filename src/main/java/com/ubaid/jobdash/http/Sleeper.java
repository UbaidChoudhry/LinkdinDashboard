package com.ubaid.jobdash.http;

import java.time.Duration;

/**
 * Injectable wait abstraction so pacing delays can be asserted in tests without a real sleep.
 */
public interface Sleeper {

    /**
     * Blocks the calling thread for approximately {@code duration}. Must be interruptible:
     * implementations should propagate {@link InterruptedException} promptly rather than
     * swallowing or retrying it.
     */
    void sleep(Duration duration) throws InterruptedException;

    /** The real, thread-blocking implementation used outside tests. */
    static Sleeper real() {
        return duration -> {
            long ms = duration.toMillis();
            if (ms > 0) {
                Thread.sleep(ms);
            }
        };
    }
}
