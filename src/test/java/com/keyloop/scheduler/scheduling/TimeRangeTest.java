package com.keyloop.scheduler.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TimeRangeTest {

    @Test
    void backToBackRangesDoNotOverlap() {
        assertThat(range("09:00", "10:00").overlaps(range("10:00", "11:00"))).isFalse();
        assertThat(range("10:00", "11:00").overlaps(range("09:00", "10:00"))).isFalse();
    }

    @Test
    void partialAndNestedRangesOverlap() {
        assertThat(range("10:00", "11:00").overlaps(range("10:45", "11:15"))).isTrue();
        assertThat(range("10:00", "12:00").overlaps(range("10:30", "11:00"))).isTrue();
        assertThat(range("10:30", "11:00").overlaps(range("10:00", "12:00"))).isTrue();
    }

    @Test
    void containsIncludesBothEnds() {
        assertThat(range("08:00", "18:00").contains(range("08:00", "18:00"))).isTrue();
        assertThat(range("08:00", "18:00").contains(range("17:00", "18:30"))).isFalse();
        assertThat(range("08:00", "18:00").contains(range("07:30", "08:30"))).isFalse();
    }

    @Test
    void ofStartAndDuration() {
        TimeRange range = TimeRange.of(at("10:00"), Duration.ofMinutes(90));

        assertThat(range.end()).isEqualTo(at("11:30"));
        assertThat(range.minutes()).isEqualTo(90);
    }

    @Test
    void rejectsEmptyOrInvertedRanges() {
        assertThatThrownBy(() -> range("10:00", "10:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range("11:00", "10:00")).isInstanceOf(IllegalArgumentException.class);
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(at(start), at(end));
    }

    private static Instant at(String time) {
        return Instant.parse("2026-11-03T" + time + ":00Z");
    }
}
