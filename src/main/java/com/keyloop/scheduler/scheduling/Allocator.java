package com.keyloop.scheduler.scheduling;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Allocator {

    private Allocator() {
    }

    public static Optional<Allocation> allocate(TimeRange wanted, List<Long> bayIds, List<Long> technicianIds,
            List<Commitment> dayCommitments) {
        FreeResources free = FreeResources.during(wanted, bayIds, technicianIds, dayCommitments);
        Map<Long, Long> bookedMinutes = new HashMap<>();
        for (Commitment commitment : dayCommitments) {
            bookedMinutes.merge(commitment.technicianId(), commitment.range().minutes(), Long::sum);
        }
        Optional<Long> bay = free.bayIds().stream().min(Comparator.naturalOrder());
        Optional<Long> technician = free.technicianIds().stream()
                .min(Comparator.<Long>comparingLong(id -> bookedMinutes.getOrDefault(id, 0L))
                        .thenComparing(Comparator.naturalOrder()));
        if (bay.isEmpty() || technician.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Allocation(bay.get(), technician.get()));
    }
}
