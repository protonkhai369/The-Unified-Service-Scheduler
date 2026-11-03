package com.keyloop.scheduler.availability.controller;

import java.time.LocalDate;

import jakarta.validation.constraints.Positive;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.keyloop.scheduler.availability.dto.response.AvailabilityResponse;
import com.keyloop.scheduler.availability.service.AvailabilityService;

import io.swagger.v3.oas.annotations.Parameter;

@RestController
class AvailabilityController {

    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/api/v1/dealerships/{dealershipId}/availability")
    AvailabilityResponse availability(@PathVariable @Positive long dealershipId,
            @RequestParam @Positive @Parameter(example = "1") long serviceTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "A date at the dealership", example = "2026-11-03") LocalDate date) {
        return this.availability.availability(dealershipId, serviceTypeId, date);
    }
}
