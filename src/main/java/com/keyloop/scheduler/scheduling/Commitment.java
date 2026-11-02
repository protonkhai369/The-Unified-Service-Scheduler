package com.keyloop.scheduler.scheduling;

/** A confirmed appointment that occupies a bay and a technician for its range. */
public record Commitment(long bayId, long technicianId, TimeRange range) {
}
