package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * One local calendar day of a dealership, in the dealership's own time zone. Answers the calendar
 * questions (opening hours, the slot grid, where the day starts and ends) that cannot be answered on
 * instants alone; {@code java.time} applies daylight saving shifts.
 */
public record BusinessDay(LocalDate date, ZoneId zone, LocalTime opens, LocalTime closes) {

    /** The dealership's day that {@code instant} falls on, in the dealership's zone. */
    public static BusinessDay containing(Instant instant, ZoneId zone, LocalTime opens, LocalTime closes) {
        return new BusinessDay(LocalDate.ofInstant(instant, zone), zone, opens, closes);
    }

    public TimeRange openingHours() {
        return new TimeRange(at(this.opens), at(this.closes));
    }

    /** From this local midnight to the next one. */
    public TimeRange wholeDay() {
        return new TimeRange(this.date.atStartOfDay(this.zone).toInstant(),
                this.date.plusDays(1).atStartOfDay(this.zone).toInstant());
    }

    /**
     * Whether {@code start}, read as a local clock time of this dealership, is a whole number of
     * {@code step}s after opening time. Counted on the local clock rather than on instants, so a client
     * in an offset such as {@code +05:45} gets the same grid as everyone else.
     */
    public boolean isOnGrid(Instant start, Duration step) {
        LocalTime local = LocalTime.ofInstant(start, this.zone);
        if (local.getSecond() != 0 || local.getNano() != 0) {
            return false;
        }
        return ChronoUnit.MINUTES.between(this.opens, local) % step.toMinutes() == 0;
    }

    private Instant at(LocalTime time) {
        return ZonedDateTime.of(this.date, time, this.zone).toInstant();
    }
}
