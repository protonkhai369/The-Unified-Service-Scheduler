package com.keyloop.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {

    public static final Instant TEST_START = Instant.parse("2026-11-02T00:00:00Z");

    private volatile Instant now = TEST_START;

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = this.now.plus(duration);
    }

    public void reset() {
        set(TEST_START);
    }

    @Override
    public Instant instant() {
        return this.now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The application only uses the UTC clock");
    }
}
