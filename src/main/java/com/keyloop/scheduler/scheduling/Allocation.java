package com.keyloop.scheduler.scheduling;

/** The bay and technician chosen for a booking. */
public record Allocation(long bayId, long technicianId) {
}
