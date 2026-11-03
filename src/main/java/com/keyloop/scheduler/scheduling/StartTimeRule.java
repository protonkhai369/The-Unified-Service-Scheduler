package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class StartTimeRule {

    private StartTimeRule() {
    }

    public static Optional<StartTimeViolation> check(TimeRange wanted, Instant now, BusinessDay day, Duration step) {
        if (!wanted.start().isAfter(now)) {
            return Optional.of(StartTimeViolation.IN_PAST);
        }
        if (!day.openingHours().contains(wanted)) {
            return Optional.of(StartTimeViolation.OUTSIDE_OPENING_HOURS);
        }
        if (!day.isOnGrid(wanted.start(), step)) {
            return Optional.of(StartTimeViolation.NOT_ON_GRID);
        }
        return Optional.empty();
    }
}
