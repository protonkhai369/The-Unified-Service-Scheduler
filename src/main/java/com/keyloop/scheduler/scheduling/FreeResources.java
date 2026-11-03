package com.keyloop.scheduler.scheduling;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record FreeResources(List<Long> bayIds, List<Long> technicianIds) {

    public static FreeResources during(TimeRange wanted, List<Long> bayIds, List<Long> technicianIds,
            List<Commitment> commitments) {
        Set<Long> busyBays = new HashSet<>();
        Set<Long> busyTechnicians = new HashSet<>();
        for (Commitment commitment : commitments) {
            if (commitment.range().overlaps(wanted)) {
                busyBays.add(commitment.bayId());
                busyTechnicians.add(commitment.technicianId());
            }
        }
        return new FreeResources(bayIds.stream().filter(id -> !busyBays.contains(id)).toList(),
                technicianIds.stream().filter(id -> !busyTechnicians.contains(id)).toList());
    }
}
