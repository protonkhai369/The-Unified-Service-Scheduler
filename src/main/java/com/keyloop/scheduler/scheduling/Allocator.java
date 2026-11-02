package com.keyloop.scheduler.scheduling;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses a bay and a technician that are both free for a whole range. Deterministic, so the same
 * inputs always give the same pair: the lowest free bay id, and the free technician with the fewest
 * booked minutes that day (ties to the lowest id).
 */
public final class Allocator {

    private Allocator() {
    }

    /**
     * @param bayIds the dealership's active bays
     * @param technicianIds the dealership's active technicians qualified for the service type
     * @param dayCommitments every confirmed appointment of the dealership on the wanted range's local
     * day; they both block resources and count towards each technician's booked minutes
     * @return empty when no bay or no qualified technician is free for the whole range
     */
    public static Optional<Allocation> allocate(TimeRange wanted, List<Long> bayIds, List<Long> technicianIds,
            List<Commitment> dayCommitments) {
        Set<Long> busyBays = new HashSet<>();
        Set<Long> busyTechnicians = new HashSet<>();
        Map<Long, Long> bookedMinutes = new HashMap<>();
        for (Commitment commitment : dayCommitments) {
            bookedMinutes.merge(commitment.technicianId(), commitment.range().minutes(), Long::sum);
            if (commitment.range().overlaps(wanted)) {
                busyBays.add(commitment.bayId());
                busyTechnicians.add(commitment.technicianId());
            }
        }
        Optional<Long> bay = bayIds.stream()
                .filter(id -> !busyBays.contains(id))
                .min(Comparator.naturalOrder());
        Optional<Long> technician = technicianIds.stream()
                .filter(id -> !busyTechnicians.contains(id))
                .min(Comparator.<Long>comparingLong(id -> bookedMinutes.getOrDefault(id, 0L))
                        .thenComparing(Comparator.naturalOrder()));
        if (bay.isEmpty() || technician.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Allocation(bay.get(), technician.get()));
    }
}
