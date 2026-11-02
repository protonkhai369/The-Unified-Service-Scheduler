package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;

/** A booked appointment, and whether it was replayed through its idempotency key rather than created. */
public record BookingResult(AppointmentResponse appointment, boolean replayed) {
}
