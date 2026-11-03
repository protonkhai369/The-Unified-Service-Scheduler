package com.keyloop.scheduler.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.service.BookingAttempt.LostRace;
import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.TimeRange;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

class BookingFailuresTest {

    private static final BookingContext CONTEXT = new BookingContext(1, 2, 3, 4,
            new BusinessDay(LocalDate.parse("2026-11-03"), ZoneOffset.UTC, LocalTime.of(8, 0), LocalTime.of(18, 0)),
            TimeRange.of(Instant.parse("2026-11-03T10:00:00Z"), Duration.ofHours(1)), "key-1", "f".repeat(64));

    private final AppointmentRepository appointments = mock(AppointmentRepository.class);

    private final BookingFailures failures = new BookingFailures(new BookingReplays(appointments));

    @Test
    void deadlockIsALostRace() {
        assertThat(failures.classify(failure("40P01", null), CONTEXT)).isEqualTo(new LostRace("deadlock"));
    }

    @Test
    void lockTimeoutIsSlotContention() {
        assertThatThrownBy(() -> failures.classify(failure("55P03", null), CONTEXT))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SLOT_CONTENTION));
    }

    @Test
    void bayOrTechnicianOverlapIsALostRace() {
        when(appointments.findViewByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        assertThat(failures.classify(failure("23P01", "appointment_no_bay_overlap"), CONTEXT))
                .isEqualTo(new LostRace("appointment_no_bay_overlap"));
        assertThat(failures.classify(failure("23P01", "appointment_no_technician_overlap"), CONTEXT))
                .isEqualTo(new LostRace("appointment_no_technician_overlap"));
    }

    @Test
    void vehicleOverlapIsVehicleAlreadyBooked() {
        when(appointments.findViewByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> failures.classify(failure("23P01", "appointment_no_vehicle_overlap"), CONTEXT))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VEHICLE_ALREADY_BOOKED));
    }

    @Test
    void otherDatabaseFailuresAreRethrown() {
        DataAccessException checkViolation = failure("23514", "appointment_time_order");
        DataAccessException notPostgres = new DataIntegrityViolationException("no driver error inside");

        assertThatThrownBy(() -> failures.classify(checkViolation, CONTEXT)).isSameAs(checkViolation);
        assertThatThrownBy(() -> failures.classify(notPostgres, CONTEXT)).isSameAs(notPostgres);
    }

    private static DataAccessException failure(String sqlState, String constraint) {
        String fields = "SERROR\0C" + sqlState + "\0Mtest failure\0" + (constraint != null ? "n" + constraint + "\0" : "");
        return new DataIntegrityViolationException("translated", new PSQLException(new ServerErrorMessage(fields)));
    }
}
