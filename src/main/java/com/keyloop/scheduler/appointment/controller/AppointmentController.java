package com.keyloop.scheduler.appointment.controller;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.appointment.dto.request.BookAppointmentRequest;
import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.appointment.service.BookingResult;
import com.keyloop.scheduler.appointment.service.BookingService;

/**
 * Service appointments. Not annotated with {@code @Validated}: the header and body constraints are
 * checked by Spring MVC's built-in method validation, which the exception handler reports as
 * {@code VALIDATION_FAILED}.
 */
@RestController
@RequestMapping(AppointmentController.PATH)
class AppointmentController {

    static final String PATH = "/api/v1/appointments";

    private final BookingService bookings;

    private final AppointmentService appointments;

    AppointmentController(BookingService bookings, AppointmentService appointments) {
        this.bookings = bookings;
        this.appointments = appointments;
    }

    /**
     * {@code 201} with the new appointment, or with the original one and {@code Idempotent-Replayed: true}.
     * {@code @ResponseStatus} documents the 201 in OpenAPI; the returned entity sets the same status.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") @Nullable String idempotencyKey) {
        BookingResult result = this.bookings.book(request, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(PATH + "/" + result.appointment().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.appointment());
    }

    @GetMapping("/{id}")
    AppointmentResponse appointment(@PathVariable UUID id) {
        return this.appointments.appointment(id);
    }

    /** Idempotent: cancelling a cancelled appointment returns it unchanged. */
    @PostMapping("/{id}/cancel")
    AppointmentResponse cancel(@PathVariable UUID id) {
        return this.appointments.cancel(id);
    }
}
