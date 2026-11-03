package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.TimeRange;

record BookingContext(long dealershipId, long customerId, long vehicleId, long serviceTypeId, BusinessDay day,
        TimeRange wanted, String idempotencyKey, String fingerprint) {
}
