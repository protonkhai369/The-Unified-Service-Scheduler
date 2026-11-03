package com.keyloop.scheduler.appointment.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

@Component
class BookingReplays {

    private final AppointmentRepository appointments;

    BookingReplays(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    Optional<AppointmentResponse> find(String idempotencyKey, String fingerprint) {
        return this.appointments.findViewByIdempotencyKey(idempotencyKey).map(view -> {
            if (!view.appointment().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return AppointmentResponse.of(view);
        });
    }
}
