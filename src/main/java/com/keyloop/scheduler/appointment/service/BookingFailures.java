package com.keyloop.scheduler.appointment.service;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.service.BookingAttempt.LostRace;
import com.keyloop.scheduler.appointment.service.BookingAttempt.Outcome;
import com.keyloop.scheduler.appointment.service.BookingAttempt.Replayed;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

@Component
class BookingFailures {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String EXCLUSION_VIOLATION = "23P01";

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String DEADLOCK_DETECTED = "40P01";

    private final BookingReplays replays;

    BookingFailures(BookingReplays replays) {
        this.replays = replays;
    }

    Outcome classify(DataAccessException failure, BookingContext context) {
        PSQLException error = postgresError(failure).orElseThrow(() -> failure);
        String sqlState = error.getSQLState();
        if (LOCK_NOT_AVAILABLE.equals(sqlState)) {
            throw new ApiException(ErrorCode.SLOT_CONTENTION);
        }
        if (DEADLOCK_DETECTED.equals(sqlState)) {
            return new LostRace("deadlock");
        }
        if (!UNIQUE_VIOLATION.equals(sqlState) && !EXCLUSION_VIOLATION.equals(sqlState)) {
            throw failure;
        }
        Optional<AppointmentResponse> replay = this.replays.find(context.idempotencyKey(), context.fingerprint());
        if (replay.isPresent()) {
            return new Replayed(replay.get());
        }
        String constraint = error.getServerErrorMessage() != null ? error.getServerErrorMessage().getConstraint() : null;
        if ("appointment_no_vehicle_overlap".equals(constraint)) {
            throw new ApiException(ErrorCode.VEHICLE_ALREADY_BOOKED, context.vehicleId());
        }
        if ("appointment_no_bay_overlap".equals(constraint) || "appointment_no_technician_overlap".equals(constraint)
                || "appointment_idempotency_key_unique".equals(constraint)) {
            return new LostRace(constraint);
        }
        throw failure;
    }

    private static Optional<PSQLException> postgresError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException error) {
                return Optional.of(error);
            }
        }
        return Optional.empty();
    }
}
