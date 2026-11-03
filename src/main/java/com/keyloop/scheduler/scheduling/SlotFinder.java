package com.keyloop.scheduler.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SlotFinder {

    private SlotFinder() {
    }

    public static List<Slot> find(BusinessDay day, Duration duration, Duration step, Instant now, List<Long> bayIds,
            List<Long> technicianIds, List<Commitment> dayCommitments) {
        Map<Instant, Slot> slots = new TreeMap<>();
        long openMinutes = ChronoUnit.MINUTES.between(day.opens(), day.closes());
        for (long offset = 0; offset < openMinutes; offset += step.toMinutes()) {
            LocalTime local = day.opens().plusMinutes(offset);
            TimeRange range = TimeRange.of(ZonedDateTime.of(day.date(), local, day.zone()).toInstant(), duration);
            if (StartTimeRule.check(range, now, day, step).isPresent()) {
                continue;
            }
            FreeResources free = FreeResources.during(range, bayIds, technicianIds, dayCommitments);
            if (!free.bayIds().isEmpty() && !free.technicianIds().isEmpty()) {
                slots.putIfAbsent(range.start(),
                        new Slot(range, free.bayIds().size(), free.technicianIds().size()));
            }
        }
        return List.copyOf(slots.values());
    }
}
