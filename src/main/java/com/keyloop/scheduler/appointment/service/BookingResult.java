package com.keyloop.scheduler.appointment.service;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;

public record BookingResult(AppointmentResponse appointment, boolean replayed) {
}
