package com.keyloop.scheduler.appointment.dto.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /api/v1/appointments}. {@code startTime} must carry an offset; a local
 * date-time without one is rejected while parsing, because it is ambiguous.
 */
public record BookAppointmentRequest(
        @NotNull @Positive @Schema(example = "1") Long dealershipId,
        @NotNull @Positive @Schema(example = "1") Long customerId,
        @NotNull @Positive @Schema(example = "1") Long vehicleId,
        @NotNull @Positive @Schema(example = "1") Long serviceTypeId,
        @NotNull @Schema(example = "2026-11-03T10:00:00Z") OffsetDateTime startTime) {
}
