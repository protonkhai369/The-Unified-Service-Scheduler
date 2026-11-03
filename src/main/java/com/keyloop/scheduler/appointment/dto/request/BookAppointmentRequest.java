package com.keyloop.scheduler.appointment.dto.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;

public record BookAppointmentRequest(
        @NotNull @Positive @Schema(example = "1") Long dealershipId,
        @NotNull @Positive @Schema(example = "1") Long customerId,
        @NotNull @Positive @Schema(example = "1") Long vehicleId,
        @NotNull @Positive @Schema(example = "1") Long serviceTypeId,
        @NotNull @Schema(example = "2026-11-03T10:00:00Z") OffsetDateTime startTime) {
}
