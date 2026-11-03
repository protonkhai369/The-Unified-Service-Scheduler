package com.keyloop.scheduler.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlotFinderTest {

    private static final BusinessDay DAY = new BusinessDay(LocalDate.parse("2026-11-03"), ZoneOffset.UTC,
            LocalTime.of(8, 0), LocalTime.of(18, 0));

    private static final Duration STEP = Duration.ofMinutes(30);

    private static final Duration HOUR = Duration.ofMinutes(60);

    private static final Instant DAY_BEFORE = Instant.parse("2026-11-02T12:00:00Z");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final LocalDate BST_STARTS = LocalDate.parse("2026-03-29");

    @Test
    @DisplayName("Slots respect opening hours")
    void slotsRespectOpeningHours() {
        List<Slot> slots = find(HOUR, DAY_BEFORE, List.of());

        assertThat(starts(slots)).containsExactly(times("08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00",
                "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30",
                "17:00"));
        assertThat(slots.getLast().range()).isEqualTo(range("17:00", "18:00"));
    }

    @Test
    @DisplayName("Service longer than the step")
    void serviceLongerThanTheStep() {
        List<Slot> slots = find(Duration.ofMinutes(90), DAY_BEFORE, List.of());

        assertThat(slots.getLast().range()).isEqualTo(range("16:30", "18:00"));
    }

    @Test
    @DisplayName("Same-day query")
    void sameDayQuery() {
        assertThat(starts(find(HOUR, at("08:45"), List.of())).getFirst()).isEqualTo(at("09:00"));
    }

    @Test
    @DisplayName("Query at an exact slot time")
    void queryAtAnExactSlotTime() {
        assertThat(starts(find(HOUR, at("09:00"), List.of())).getFirst())
                .as("booking rejects a start equal to now")
                .isEqualTo(at("09:30"));
    }

    @Test
    @DisplayName("Today after the last start")
    void todayAfterTheLastStart() {
        assertThat(find(HOUR, at("17:30"), List.of())).isEmpty();
    }

    @Test
    @DisplayName("Remaining capacity is reported")
    void remainingCapacityIsReported() {
        List<Commitment> day = List.of(new Commitment(1, 11, range("10:00", "11:00")));

        List<Slot> slots = SlotFinder.find(DAY, HOUR, STEP, DAY_BEFORE, List.of(1L, 2L), List.of(11L, 12L, 13L), day);

        assertThat(slots).contains(new Slot(range("10:00", "11:00"), 1, 2), new Slot(range("11:00", "12:00"), 2, 3));
    }

    @Test
    @DisplayName("Fully booked time is hidden")
    void fullyBookedTimeIsHidden() {
        List<Commitment> day = List.of(
                new Commitment(1, 11, range("10:00", "11:00")),
                new Commitment(2, 12, range("10:00", "11:00")));

        List<Slot> slots = SlotFinder.find(DAY, HOUR, STEP, DAY_BEFORE, List.of(1L, 2L), List.of(11L, 12L, 13L), day);

        assertThat(starts(slots)).doesNotContain(times("09:30", "10:00", "10:30")).contains(times("09:00", "11:00"));
    }

    @Test
    @DisplayName("Bays free but no qualified technician free")
    void baysFreeButNoQualifiedTechnicianFree() {
        List<Commitment> day = List.of(new Commitment(1, 11, range("10:00", "11:00")));

        List<Slot> slots = SlotFinder.find(DAY, HOUR, STEP, DAY_BEFORE, List.of(1L, 2L), List.of(11L), day);

        assertThat(starts(slots)).doesNotContain(times("09:30", "10:00", "10:30")).contains(times("09:00", "11:00"));
    }

    @Test
    @DisplayName("Back-to-back start is listed")
    void backToBackStartIsListed() {
        List<Slot> slots = find(HOUR, DAY_BEFORE, List.of(new Commitment(1, 11, range("09:00", "10:00"))));

        assertThat(starts(slots)).contains(at("10:00")).doesNotContain(times("09:00", "09:30"));
    }

    @Test
    @DisplayName("Daylight saving day")
    void daylightSavingDay() {
        BusinessDay london = new BusinessDay(BST_STARTS, LONDON, LocalTime.of(8, 0), LocalTime.of(18, 0));

        List<Slot> slots = SlotFinder.find(london, HOUR, STEP, Instant.parse("2026-03-28T12:00:00Z"), List.of(1L),
                List.of(11L), List.of());

        assertThat(slots).hasSize(19);
        assertThat(slots.getFirst().range().start()).isEqualTo(Instant.parse("2026-03-29T07:00:00Z"));
        assertThat(slots.getLast().range()).isEqualTo(
                new TimeRange(Instant.parse("2026-03-29T16:00:00Z"), Instant.parse("2026-03-29T17:00:00Z")));
    }

    @Test
    void aDaylightSavingGapInsideOpeningHoursYieldsNoDuplicateStart() {
        BusinessDay london = new BusinessDay(BST_STARTS, LONDON, LocalTime.MIDNIGHT, LocalTime.of(4, 0));

        List<Slot> slots = SlotFinder.find(london, HOUR, STEP, Instant.parse("2026-03-28T12:00:00Z"), List.of(1L),
                List.of(11L), List.of());

        assertThat(starts(slots)).containsExactly(Instant.parse("2026-03-29T00:00:00Z"),
                Instant.parse("2026-03-29T00:30:00Z"), Instant.parse("2026-03-29T01:00:00Z"),
                Instant.parse("2026-03-29T01:30:00Z"), Instant.parse("2026-03-29T02:00:00Z"));
    }

    @Test
    void listsExactlyTheStartsBookingWouldAccept() {
        for (long seed = 1; seed <= 400; seed++) {
            Case scenario = Case.random(new Random(seed));

            List<Slot> expected = new ArrayList<>();
            TimeRange wholeDay = scenario.day().wholeDay();
            for (Instant start = wholeDay.start(); start.isBefore(wholeDay.end()); start = start.plus(1, ChronoUnit.MINUTES)) {
                TimeRange wanted = TimeRange.of(start, scenario.duration());
                if (StartTimeRule.check(wanted, scenario.now(), scenario.day(), scenario.step()).isEmpty()
                        && Allocator.allocate(wanted, scenario.bayIds(), scenario.technicianIds(),
                                scenario.commitments()).isPresent()) {
                    expected.add(new Slot(wanted, freeCount(scenario.bayIds(), Commitment::bayId, wanted, scenario),
                            freeCount(scenario.technicianIds(), Commitment::technicianId, wanted, scenario)));
                }
            }

            assertThat(SlotFinder.find(scenario.day(), scenario.duration(), scenario.step(), scenario.now(),
                    scenario.bayIds(), scenario.technicianIds(), scenario.commitments()))
                    .as("seed %d: %s", seed, scenario)
                    .isEqualTo(expected);
        }
    }

    private static int freeCount(List<Long> ids, ToLongFunction<Commitment> resource, TimeRange wanted, Case scenario) {
        int free = 0;
        for (long id : ids) {
            boolean busy = scenario.commitments().stream()
                    .anyMatch(commitment -> resource.applyAsLong(commitment) == id
                            && commitment.range().start().isBefore(wanted.end())
                            && wanted.start().isBefore(commitment.range().end()));
            if (!busy) {
                free++;
            }
        }
        return free;
    }

    private record Case(BusinessDay day, Duration duration, Duration step, Instant now, List<Long> bayIds,
            List<Long> technicianIds, List<Commitment> commitments) {

        private static final List<ZoneId> ZONES = List.of(ZoneOffset.UTC, ZoneId.of("Asia/Ho_Chi_Minh"), LONDON);

        private static final List<LocalDate> DATES = List.of(LocalDate.parse("2026-11-03"), BST_STARTS,
                LocalDate.parse("2026-10-25"));

        static Case random(Random random) {
            LocalTime opens = LocalTime.of(6 + random.nextInt(5), 15 * random.nextInt(4));
            LocalTime closes = opens.plusHours(2 + random.nextInt(10)).plusMinutes(15L * random.nextInt(4));
            BusinessDay day = new BusinessDay(pick(random, DATES), pick(random, ZONES), opens, closes);
            Duration duration = Duration.ofMinutes(pick(random, List.of(30, 45, 60, 90, 120)));
            Duration step = Duration.ofMinutes(pick(random, List.of(15, 30, 60)));

            Instant midnight = day.wholeDay().start();
            Instant now = midnight.plus(random.nextInt(-120, 1560), ChronoUnit.MINUTES);
            if (random.nextBoolean()) {
                now = now.plusSeconds(1 + random.nextInt(59));
            }
            List<Long> bayIds = ids(1, random.nextInt(4));
            List<Long> technicianIds = ids(11, random.nextInt(4));
            List<Commitment> commitments = new ArrayList<>();
            for (int i = random.nextInt(7); i > 0; i--) {
                Instant start = midnight.plus(random.nextInt(1410), ChronoUnit.MINUTES);
                commitments.add(new Commitment(1 + random.nextInt(4), 11 + random.nextInt(4),
                        TimeRange.of(start, Duration.ofMinutes(15 + random.nextInt(166)))));
            }
            return new Case(day, duration, step, now, bayIds, technicianIds, List.copyOf(commitments));
        }

        private static List<Long> ids(long first, int count) {
            return LongStream.range(first, first + count).boxed().toList();
        }

        private static <T> T pick(Random random, List<T> values) {
            return values.get(random.nextInt(values.size()));
        }
    }

    private static List<Slot> find(Duration duration, Instant now, List<Commitment> commitments) {
        return SlotFinder.find(DAY, duration, STEP, now, List.of(1L), List.of(11L), commitments);
    }

    private static List<Instant> starts(List<Slot> slots) {
        return slots.stream().map(slot -> slot.range().start()).toList();
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(at(start), at(end));
    }

    private static Instant at(String time) {
        return Instant.parse("2026-11-03T" + time + ":00Z");
    }

    private static Instant[] times(String... times) {
        return Arrays.stream(times).map(SlotFinderTest::at).toArray(Instant[]::new);
    }
}
