package com.keyloop.scheduler.appointment.repository;

import java.time.Instant;

import com.keyloop.scheduler.scheduling.Commitment;
import com.keyloop.scheduler.scheduling.TimeRange;

public record BookedSlot(long serviceBayId, long technicianId, Instant startAt, Instant endAt) {

    public Commitment toCommitment() {
        return new Commitment(this.serviceBayId, this.technicianId, new TimeRange(this.startAt, this.endAt));
    }
}
