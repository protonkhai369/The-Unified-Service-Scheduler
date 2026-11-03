package com.keyloop.scheduler.appointment.controller;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import com.keyloop.scheduler.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "A Supabase Auth access token")
@SecurityRequirement(name = "bearer")
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") @Nullable String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser caller) {
        BookingResult result = this.bookings.book(request, idempotencyKey, caller);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(PATH + "/" + result.appointment().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.appointment());
    }

    @GetMapping("/{id}")
    AppointmentResponse appointment(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return this.appointments.appointment(id, caller);
    }

    @PostMapping("/{id}/cancel")
    AppointmentResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return this.appointments.cancel(id, caller);
    }
}
