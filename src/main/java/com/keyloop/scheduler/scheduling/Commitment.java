package com.keyloop.scheduler.scheduling;

public record Commitment(long bayId, long technicianId, TimeRange range) {
}
