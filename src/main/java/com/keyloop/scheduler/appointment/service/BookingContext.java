package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.TimeRange;

/** A validated booking request: everything an allocation attempt needs, without further lookups. */
record BookingContext(long dealershipId, long customerId, long vehicleId, long serviceTypeId, BusinessDay day,
        TimeRange wanted, String idempotencyKey, String fingerprint) {
}
