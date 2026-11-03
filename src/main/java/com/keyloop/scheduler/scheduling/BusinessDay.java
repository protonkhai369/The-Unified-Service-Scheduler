package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public record BusinessDay(LocalDate date, ZoneId zone, LocalTime opens, LocalTime closes) {

    public static BusinessDay containing(Instant instant, ZoneId zone, LocalTime opens, LocalTime closes) {
        return new BusinessDay(LocalDate.ofInstant(instant, zone), zone, opens, closes);
    }

    public TimeRange openingHours() {
        return new TimeRange(at(this.opens), at(this.closes));
    }

    public TimeRange wholeDay() {
        return new TimeRange(this.date.atStartOfDay(this.zone).toInstant(),
                this.date.plusDays(1).atStartOfDay(this.zone).toInstant());
    }

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
