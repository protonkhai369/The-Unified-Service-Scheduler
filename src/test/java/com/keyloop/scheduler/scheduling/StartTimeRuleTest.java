package com.keyloop.scheduler.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class StartTimeRuleTest {

    private static final BusinessDay DAY = new BusinessDay(LocalDate.parse("2026-11-03"), ZoneOffset.UTC,
            LocalTime.of(8, 0), LocalTime.of(18, 0));

    private static final Duration STEP = Duration.ofMinutes(30);

    private static final Instant NOW = at("09:00");

    @Test
    void acceptsAFutureStartOnTheGridWithinOpeningHours() {
        assertThat(check("10:00", 60)).isEmpty();
        assertThat(check("17:00", 60)).as("ends exactly at closing time").isEmpty();
    }

    @Test
    void startAtTheCurrentInstantIsInThePast() {
        assertThat(check("09:00", 60)).contains(StartTimeViolation.IN_PAST);
        assertThat(check("08:30", 60)).contains(StartTimeViolation.IN_PAST);
    }

    @Test
    void serviceMustStartAndEndWithinOpeningHours() {
        Instant earlyMorning = at("06:00");
        assertThat(StartTimeRule.check(TimeRange.of(at("07:00"), Duration.ofMinutes(60)), earlyMorning, DAY, STEP))
                .contains(StartTimeViolation.OUTSIDE_OPENING_HOURS);
        assertThat(check("17:00", 90)).contains(StartTimeViolation.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void startMustBeOnTheGrid() {
        assertThat(check("10:15", 60)).contains(StartTimeViolation.NOT_ON_GRID);
    }

    @Test
    void outsideOpeningHoursIsReportedBeforeOffGrid() {
        Instant earlyMorning = at("06:00");
        assertThat(StartTimeRule.check(TimeRange.of(at("07:45"), Duration.ofMinutes(60)), earlyMorning, DAY, STEP))
                .contains(StartTimeViolation.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void inThePastIsReportedFirst() {
        Instant evening = at("19:00");
        assertThat(StartTimeRule.check(TimeRange.of(at("17:45"), Duration.ofMinutes(60)), evening, DAY, STEP))
                .contains(StartTimeViolation.IN_PAST);
    }

    private static java.util.Optional<StartTimeViolation> check(String start, int minutes) {
        return StartTimeRule.check(TimeRange.of(at(start), Duration.ofMinutes(minutes)), NOW, DAY, STEP);
    }

    private static Instant at(String time) {
        return Instant.parse("2026-11-03T" + time + ":00Z");
    }
}
