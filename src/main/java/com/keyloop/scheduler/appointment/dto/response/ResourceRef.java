package com.keyloop.scheduler.appointment.dto.response;

/** The allocated service bay or technician of an appointment. */
public record ResourceRef(long id, String name) {
}
