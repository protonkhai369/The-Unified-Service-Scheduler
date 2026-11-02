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

/**
 * Turns the database's rejection of a booking attempt into an outcome. Reads the SQLSTATE and the
 * constraint name from PostgreSQL's own error (the constraint names are a contract with V1) and never
 * parses messages. The idempotency key is looked up before the constraint is considered: a duplicate
 * request that collided with its original on the vehicle or a bay is a replay, not a conflict.
 */
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

    /**
     * Runs after the attempt's transaction has rolled back, so the key lookup sees committed data.
     *
     * @throws ApiException {@code SLOT_CONTENTION}, {@code VEHICLE_ALREADY_BOOKED} or
     * {@code IDEMPOTENCY_KEY_REUSED}
     * @throws DataAccessException {@code failure} itself when it is not a known booking conflict
     */
    Outcome classify(DataAccessException failure, BookingContext context) {
        PSQLException error = postgresError(failure).orElseThrow(() -> failure);
        String sqlState = error.getSQLState();
        if (LOCK_NOT_AVAILABLE.equals(sqlState)) {
            throw new ApiException(ErrorCode.SLOT_CONTENTION);
        }
        if (DEADLOCK_DETECTED.equals(sqlState)) {
            // Two inserts that do not share a lock (one vehicle at two dealerships, or a writer bypassing
            // the lock) each wrote their index entry and then waited for the other's exclusion check.
            // PostgreSQL aborted this one; the next attempt sees the survivor's committed row.
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
                // The key's earlier owner rolled back after blocking this insert: nothing to replay, try again.
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
