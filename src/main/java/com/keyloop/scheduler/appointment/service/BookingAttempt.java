package com.keyloop.scheduler.appointment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.entity.Appointment;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.BookedSlot;
import com.keyloop.scheduler.catalog.entity.ServiceBay;
import com.keyloop.scheduler.catalog.entity.Technician;
import com.keyloop.scheduler.catalog.repository.ServiceBayRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianRepository;
import com.keyloop.scheduler.config.SchedulerProperties;
import com.keyloop.scheduler.scheduling.Allocation;
import com.keyloop.scheduler.scheduling.Allocator;
import com.keyloop.scheduler.scheduling.Commitment;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

/**
 * One allocation attempt in one {@code READ COMMITTED} transaction. It first queues on a transaction-scoped
 * advisory lock for the dealership and local day, so bookings that could compete for a bay or a
 * technician run one after another and each sees the bookings committed before it. The exclusion
 * constraints remain the guarantee; the lock only stops concurrent requests from failing because of
 * each other. A database failure is classified by {@link BookingFailures}.
 */
@Component
class BookingAttempt {

    /** What one attempt achieved; failures that end the booking are thrown as {@link ApiException}. */
    sealed interface Outcome {
    }

    record Confirmed(AppointmentResponse appointment) implements Outcome {
    }

    record Replayed(AppointmentResponse appointment) implements Outcome {
    }

    /** Another writer took the allocated bay or technician first; the next attempt reads fresh data. */
    record LostRace(String constraint) implements Outcome {
    }

    private final TransactionTemplate transaction;

    private final JdbcClient jdbc;

    private final AppointmentRepository appointments;

    private final ServiceBayRepository serviceBays;

    private final TechnicianRepository technicians;

    private final BookingReplays replays;

    private final BookingFailures failures;

    private final SchedulerProperties properties;

    private final Clock clock;

    BookingAttempt(PlatformTransactionManager transactionManager, JdbcClient jdbc, AppointmentRepository appointments,
            ServiceBayRepository serviceBays, TechnicianRepository technicians, BookingReplays replays,
            BookingFailures failures, SchedulerProperties properties, Clock clock) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.appointments = appointments;
        this.serviceBays = serviceBays;
        this.technicians = technicians;
        this.replays = replays;
        this.failures = failures;
        this.properties = properties;
        this.clock = clock;
    }

    /** The advisory lock key of a dealership-day; hash collisions only serialise unrelated days. */
    static String lockKey(long dealershipId, LocalDate date) {
        return "booking:" + dealershipId + ":" + date;
    }

    Outcome run(BookingContext context) {
        try {
            return this.transaction.execute(status -> runInTransaction(context));
        }
        catch (DataAccessException ex) {
            return this.failures.classify(ex, context);
        }
    }

    private Outcome runInTransaction(BookingContext context) {
        waitForTurn(context);
        // A duplicate of this request may have committed while this one waited.
        Optional<AppointmentResponse> replay = this.replays.find(context.idempotencyKey(), context.fingerprint());
        if (replay.isPresent()) {
            return new Replayed(replay.get());
        }
        if (this.appointments.vehicleBooked(context.vehicleId(), context.wanted().start(), context.wanted().end())) {
            throw new ApiException(ErrorCode.VEHICLE_ALREADY_BOOKED, context.vehicleId());
        }
        List<ServiceBay> bays = this.serviceBays.findByDealershipIdAndActiveTrueOrderById(context.dealershipId());
        List<Technician> qualified = this.technicians.findActiveQualified(context.dealershipId(), context.serviceTypeId());
        List<Commitment> day = this.appointments
                .findConfirmedStartingBetween(context.dealershipId(), context.day().wholeDay().start(),
                        context.day().wholeDay().end())
                .stream()
                .map(BookedSlot::toCommitment)
                .toList();
        Allocation allocation = Allocator
                .allocate(context.wanted(), bays.stream().map(ServiceBay::getId).toList(),
                        qualified.stream().map(Technician::getId).toList(), day)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_AVAILABILITY));

        Appointment appointment = Appointment.confirmed(context.dealershipId(), context.customerId(),
                context.vehicleId(), context.serviceTypeId(), allocation.bayId(), allocation.technicianId(),
                context.wanted().start(), context.wanted().end(), context.idempotencyKey(), context.fingerprint(),
                this.clock.instant());
        this.appointments.saveAndFlush(appointment);
        return new Confirmed(AppointmentResponse.of(appointment, context.day().zone(),
                bayName(bays, allocation.bayId()), technicianName(qualified, allocation.technicianId())));
    }

    /** Bounded by {@code lock_timeout}, which also bounds waits on another transaction's conflicting row. */
    private void waitForTurn(BookingContext context) {
        this.jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", this.properties.bookingLockTimeout().toMillis() + "ms")
                .query(String.class)
                .single();
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", lockKey(context.dealershipId(), context.day().date()))
                .query((row, number) -> Boolean.TRUE)
                .single();
    }

    private static String bayName(List<ServiceBay> bays, long id) {
        return bays.stream().filter(bay -> bay.getId() == id).findFirst().orElseThrow().getName();
    }

    private static String technicianName(List<Technician> technicians, long id) {
        return technicians.stream().filter(technician -> technician.getId() == id).findFirst().orElseThrow().getName();
    }
}
