package com.ubaid.jobdash.http.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A mutable {@link Clock} for tests: time only moves when {@link #advance} is called. */
public final class FakeClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public FakeClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public FakeClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void set(Instant newInstant) {
        instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new FakeClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
