package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Whether a service can start at the requested time, independent of who is free then. */
public final class StartTimeRule {

    private StartTimeRule() {
    }

    /**
     * The first rule {@code wanted} breaks: in the past, then outside opening hours, then off the grid.
     * Opening hours come before the grid because "we are closed then" tells a client more than "pick
     * another minute".
     *
     * @param day the dealership's day containing {@code wanted.start()}
     */
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
