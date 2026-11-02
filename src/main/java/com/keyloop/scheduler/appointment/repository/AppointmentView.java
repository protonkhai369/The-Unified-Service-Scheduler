package com.keyloop.scheduler.appointment.repository;

import com.keyloop.scheduler.appointment.entity.Appointment;

/** An appointment with what its response needs from other aggregates: the zone and the resource names. */
public record AppointmentView(Appointment appointment, String timeZone, String serviceBayName,
        String technicianName) {
}
