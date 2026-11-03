package com.keyloop.scheduler.appointment.controller;

import java.time.LocalDate;

import jakarta.validation.constraints.Positive;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.service.AppointmentService;
import com.keyloop.scheduler.security.AuthenticatedUser;
import com.keyloop.scheduler.web.dto.ItemsResponse;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@SecurityRequirement(name = "bearer")
@RestController
class AppointmentListController {

    private final AppointmentService appointments;

    AppointmentListController(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @GetMapping("/api/v1/me/appointments")
    ItemsResponse<AppointmentResponse> myAppointments(@AuthenticationPrincipal AuthenticatedUser caller) {
        return new ItemsResponse<>(this.appointments.appointmentsOf(caller));
    }

    @GetMapping("/api/v1/dealerships/{dealershipId}/appointments")
    ItemsResponse<AppointmentResponse> dealershipDay(@PathVariable @Positive long dealershipId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "A date at the dealership", example = "2026-11-03") LocalDate date) {
        return new ItemsResponse<>(this.appointments.dealershipDay(dealershipId, date));
    }
}
