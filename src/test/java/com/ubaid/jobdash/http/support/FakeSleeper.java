package com.ubaid.jobdash.http.support;

import com.ubaid.jobdash.http.Sleeper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link Sleeper} that never really blocks: it advances a {@link FakeClock} by the
 * requested duration and records every call, so tests can assert scheduling decisions
 * (how long, how many times) instantly. Can also be told to simulate an interrupt on the
 * next call, to test that pacing waits are interruptible.
 */
public final class FakeSleeper implements Sleeper {

    private final FakeClock clock;
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicBoolean interruptNext = new AtomicBoolean(false);

    public FakeSleeper(FakeClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void sleep(Duration duration) throws InterruptedException {
        if (interruptNext.compareAndSet(true, false)) {
            throw new InterruptedException("simulated interrupt");
        }
        sleeps.add(duration);
        clock.advance(duration);
    }

    public void interruptNextSleep() {
        interruptNext.set(true);
    }

    public synchronized List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    public synchronized int sleepCount() {
        return sleeps.size();
    }
}
