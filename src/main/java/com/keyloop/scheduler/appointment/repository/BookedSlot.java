package com.keyloop.scheduler.appointment.repository;

import java.time.Instant;

import com.keyloop.scheduler.scheduling.Commitment;
import com.keyloop.scheduler.scheduling.TimeRange;

/** The bay, technician and period of a confirmed appointment, read for allocation. */
public record BookedSlot(long serviceBayId, long technicianId, Instant startAt, Instant endAt) {

    public Commitment toCommitment() {
        return new Commitment(this.serviceBayId, this.technicianId, new TimeRange(this.startAt, this.endAt));
    }
}
