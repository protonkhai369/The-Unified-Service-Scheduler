package com.keyloop.scheduler.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class AllocatorTest {

    private static final TimeRange WANTED = range("10:00", "11:00");

    private static final List<Long> BAYS = List.of(1L, 2L, 3L);

    private static final List<Long> TECHNICIANS = List.of(11L, 12L, 13L);

    @Test
    void allocatesTheLowestBayAndTechnicianWhenEverythingIsFree() {
        assertThat(Allocator.allocate(WANTED, BAYS, TECHNICIANS, List.of())).contains(new Allocation(1, 11));
    }

    @Test
    void skipsBaysBusyForPartOfTheRange() {
        List<Commitment> day = List.of(new Commitment(1, 13, range("10:45", "11:15")));

        assertThat(Allocator.allocate(WANTED, BAYS, TECHNICIANS, day)).contains(new Allocation(2, 11));
    }

    @Test
    void lowestFreeBayWins() {
        List<Commitment> day = List.of(new Commitment(2, 13, range("10:00", "11:00")));

        assertThat(Allocator.allocate(WANTED, List.of(1L, 2L, 3L), List.of(11L, 12L), day))
                .contains(new Allocation(1, 11));
        assertThat(Allocator.allocate(WANTED, List.of(3L, 2L, 1L), List.of(11L, 12L), day))
                .as("order of the input does not matter")
                .contains(new Allocation(1, 11));
    }

    @Test
    void technicianWithFewestBookedMinutesThatDayWins() {
        List<Commitment> day = List.of(
                new Commitment(1, 11, range("08:00", "09:00")),
                new Commitment(1, 11, range("12:00", "14:00")),
                new Commitment(2, 12, range("08:00", "09:00")));

        assertThat(Allocator.allocate(WANTED, BAYS, List.of(11L, 12L), day)).contains(new Allocation(1, 12));
    }

    @Test
    void tiesGoToTheLowestTechnicianId() {
        List<Commitment> day = List.of(
                new Commitment(1, 12, range("08:00", "09:00")),
                new Commitment(2, 13, range("08:00", "09:00")));

        assertThat(Allocator.allocate(WANTED, BAYS, List.of(13L, 12L), day)).contains(new Allocation(1, 12));
    }

    @Test
    void backToBackCommitmentsDoNotBlock() {
        List<Commitment> day = List.of(
                new Commitment(1, 11, range("09:00", "10:00")),
                new Commitment(1, 11, range("11:00", "12:00")));

        assertThat(Allocator.allocate(WANTED, List.of(1L), List.of(11L), day)).contains(new Allocation(1, 11));
    }

    @Test
    void busyTechnicianIsSkippedEvenWithTheFewestMinutes() {
        List<Commitment> day = List.of(
                new Commitment(1, 11, range("10:30", "10:45")),
                new Commitment(2, 12, range("08:00", "10:00")));

        assertThat(Allocator.allocate(WANTED, BAYS, List.of(11L, 12L), day)).contains(new Allocation(2, 12));
    }

    @Test
    void noFreeBayMeansNoAllocation() {
        List<Commitment> day = List.of(new Commitment(1, 12, range("10:00", "11:00")));

        assertThat(Allocator.allocate(WANTED, List.of(1L), TECHNICIANS, day)).isEmpty();
    }

    @Test
    void noFreeTechnicianMeansNoAllocation() {
        List<Commitment> day = List.of(new Commitment(2, 11, range("10:30", "11:30")));

        assertThat(Allocator.allocate(WANTED, BAYS, List.of(11L), day)).isEmpty();
        assertThat(Allocator.allocate(WANTED, BAYS, List.of(), List.of())).isEmpty();
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse("2026-11-03T" + start + ":00Z"), Instant.parse("2026-11-03T" + end + ":00Z"));
    }
}
