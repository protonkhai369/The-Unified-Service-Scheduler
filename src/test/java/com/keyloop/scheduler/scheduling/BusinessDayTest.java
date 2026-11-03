package com.keyloop.scheduler.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class BusinessDayTest {

    private static final ZoneId SAIGON = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Test
    void openingHoursAreLocalTimesConvertedToInstants() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2026-11-03"), SAIGON, LocalTime.of(7, 30), LocalTime.of(17, 30));

        assertThat(day.openingHours())
                .isEqualTo(new TimeRange(Instant.parse("2026-11-03T00:30:00Z"), Instant.parse("2026-11-03T10:30:00Z")));
    }

    @Test
    void openingHoursFollowDaylightSavingTime() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2026-03-29"), LONDON, LocalTime.of(8, 0), LocalTime.of(18, 0));

        assertThat(day.openingHours())
                .isEqualTo(new TimeRange(Instant.parse("2026-03-29T07:00:00Z"), Instant.parse("2026-03-29T17:00:00Z")));
    }

    @Test
    void containingPicksTheLocalDateNotTheUtcDate() {
        BusinessDay day = BusinessDay.containing(Instant.parse("2026-11-02T18:00:00Z"), SAIGON, LocalTime.of(7, 30),
                LocalTime.of(17, 30));

        assertThat(day.date()).isEqualTo(LocalDate.parse("2026-11-03"));
    }

    @Test
    void wholeDayRunsFromLocalMidnightToTheNextLocalMidnight() {
        BusinessDay day = new BusinessDay(LocalDate.parse("2026-11-03"), SAIGON, LocalTime.of(7, 30), LocalTime.of(17, 30));

        assertThat(day.wholeDay())
                .isEqualTo(new TimeRange(Instant.parse("2026-11-02T17:00:00Z"), Instant.parse("2026-11-03T17:00:00Z")));
    }

    @Test
    void gridIsCountedFromOpeningTimeInLocalTime() {
        BusinessDay saigon = saigonDay();

        assertThat(saigon.isOnGrid(instant("2026-11-03T07:30:00+07:00"), HALF_HOUR)).isTrue();
        assertThat(saigon.isOnGrid(instant("2026-11-03T10:00:00+07:00"), HALF_HOUR)).isTrue();
        assertThat(saigon.isOnGrid(instant("2026-11-03T10:15:00+07:00"), HALF_HOUR)).isFalse();
    }

    @Test
    void gridIgnoresTheOffsetTheClientUsed() {
        BusinessDay saigon = saigonDay();

        assertThat(saigon.isOnGrid(instant("2026-11-03T08:45:00+05:45"), HALF_HOUR)).isTrue();
        assertThat(saigon.isOnGrid(instant("2026-11-03T10:00:00+05:45"), HALF_HOUR)).isFalse();
    }

    @Test
    void gridRejectsSecondsAndFractions() {
        BusinessDay saigon = saigonDay();

        assertThat(saigon.isOnGrid(instant("2026-11-03T10:00:30+07:00"), HALF_HOUR)).isFalse();
        assertThat(saigon.isOnGrid(instant("2026-11-03T10:00:00.5+07:00"), HALF_HOUR)).isFalse();
    }

    private static BusinessDay saigonDay() {
        return new BusinessDay(LocalDate.parse("2026-11-03"), SAIGON, LocalTime.of(7, 30), LocalTime.of(17, 30));
    }

    private static Instant instant(String offsetDateTime) {
        return OffsetDateTime.parse(offsetDateTime).toInstant();
    }
}
