package com.keyloop.scheduler.appointment.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

/**
 * Looks up an idempotency key. Used before validating a request, again once the request holds its
 * dealership-day lock, and after a constraint violation, because a duplicate request must be replayed
 * rather than reported as a conflict. Runs in the caller's transaction, or in its own when there is none.
 */
@Component
class BookingReplays {

    private final AppointmentRepository appointments;

    BookingReplays(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    /**
     * The stored appointment in its current state if the key was used for the same booking parameters;
     * empty if the key is unused.
     *
     * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED} if the key was used for different parameters
     */
    Optional<AppointmentResponse> find(String idempotencyKey, String fingerprint) {
        return this.appointments.findViewByIdempotencyKey(idempotencyKey).map(view -> {
            if (!view.appointment().getRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return AppointmentResponse.of(view);
        });
    }
}
