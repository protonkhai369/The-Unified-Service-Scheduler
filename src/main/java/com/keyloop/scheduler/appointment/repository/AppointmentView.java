package com.keyloop.scheduler.appointment.repository;

import com.keyloop.scheduler.appointment.entity.Appointment;

public record AppointmentView(Appointment appointment, String timeZone, String serviceBayName,
        String technicianName) {
}
