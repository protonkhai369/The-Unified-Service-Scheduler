package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start: " + start + " / " + end);
        }
    }

    public static TimeRange of(Instant start, Duration duration) {
        return new TimeRange(start, start.plus(duration));
    }

    public boolean overlaps(TimeRange other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }

    public boolean contains(TimeRange other) {
        return !other.start.isBefore(this.start) && !other.end.isAfter(this.end);
    }

    public long minutes() {
        return Duration.between(this.start, this.end).toMinutes();
    }
}
