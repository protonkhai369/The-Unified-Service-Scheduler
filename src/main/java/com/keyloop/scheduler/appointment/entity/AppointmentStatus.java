package com.keyloop.scheduler.appointment.entity;

/** Only {@code CONFIRMED} appointments occupy a bay, a technician and a vehicle. */
public enum AppointmentStatus {
    CONFIRMED,
    CANCELLED
}
