package com.keyloop.scheduler.appointment.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.keyloop.scheduler.appointment.dto.request.BookAppointmentRequest;
import com.keyloop.scheduler.appointment.dto.response.AppointmentResponse;
import com.keyloop.scheduler.appointment.service.BookingAttempt.Confirmed;
import com.keyloop.scheduler.appointment.service.BookingAttempt.LostRace;
import com.keyloop.scheduler.appointment.service.BookingAttempt.Outcome;
import com.keyloop.scheduler.appointment.service.BookingAttempt.Replayed;
import com.keyloop.scheduler.catalog.entity.Dealership;
import com.keyloop.scheduler.catalog.entity.ServiceType;
import com.keyloop.scheduler.catalog.repository.DealershipRepository;
import com.keyloop.scheduler.catalog.repository.ServiceTypeRepository;
import com.keyloop.scheduler.config.SchedulerProperties;
import com.keyloop.scheduler.customer.entity.Vehicle;
import com.keyloop.scheduler.customer.repository.CustomerRepository;
import com.keyloop.scheduler.customer.repository.VehicleRepository;
import com.keyloop.scheduler.scheduling.BusinessDay;
import com.keyloop.scheduler.scheduling.StartTimeRule;
import com.keyloop.scheduler.scheduling.StartTimeViolation;
import com.keyloop.scheduler.scheduling.TimeRange;
import com.keyloop.scheduler.security.AccessPolicy;
import com.keyloop.scheduler.security.AuthenticatedUser;
import com.keyloop.scheduler.web.error.ApiException;
import com.keyloop.scheduler.web.error.ErrorCode;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final TransactionTemplate readOnly;

    private final BookingAttempt attempt;

    private final BookingReplays replays;

    private final DealershipRepository dealerships;

    private final CustomerRepository customers;

    private final VehicleRepository vehicles;

    private final ServiceTypeRepository serviceTypes;

    private final BookingMetrics metrics;

    private final SchedulerProperties properties;

    private final Clock clock;

    BookingService(PlatformTransactionManager transactionManager, BookingAttempt attempt, BookingReplays replays,
            DealershipRepository dealerships, CustomerRepository customers, VehicleRepository vehicles,
            ServiceTypeRepository serviceTypes, BookingMetrics metrics, SchedulerProperties properties, Clock clock) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.attempt = attempt;
        this.replays = replays;
        this.dealerships = dealerships;
        this.customers = customers;
        this.vehicles = vehicles;
        this.serviceTypes = serviceTypes;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    public BookingResult book(BookAppointmentRequest request, @Nullable String idempotencyKey,
            AuthenticatedUser caller) {
        try {
            BookingResult result = bookCounted(request, idempotencyKey, caller);
            this.metrics.booked(result);
            return result;
        }
        catch (ApiException ex) {
            this.metrics.rejected(ex.code());
            throw ex;
        }
        catch (RuntimeException ex) {
            this.metrics.failed();
            throw ex;
        }
    }

    private BookingResult bookCounted(BookAppointmentRequest request, @Nullable String idempotencyKey,
            AuthenticatedUser caller) {
        if (!AccessPolicy.mayBook(caller, request.dealershipId(), request.customerId())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        Instant start = request.startTime().toInstant();
        String fingerprint = RequestFingerprint.of(request.dealershipId(), request.customerId(), request.vehicleId(),
                request.serviceTypeId(), start);

        Optional<AppointmentResponse> replay = this.replays.find(key, fingerprint);
        if (replay.isPresent()) {
            return replayed(replay.get());
        }
        BookingContext context = this.readOnly.execute(status -> validate(request, start, key, fingerprint));

        for (int attemptNumber = 1; attemptNumber <= this.properties.maxBookingAttempts(); attemptNumber++) {
            Outcome outcome = this.attempt.run(context);
            switch (outcome) {
                case Confirmed confirmed -> {
                    AppointmentResponse appointment = confirmed.appointment();
                    log.info("Appointment {} confirmed: dealership {}, bay {}, technician {}, {}/{}, attempt {}",
                            appointment.id(), appointment.dealershipId(), appointment.serviceBay().id(),
                            appointment.technician().id(), appointment.startTime(), appointment.endTime(),
                            attemptNumber);
                    return new BookingResult(appointment, false);
                }
                case Replayed replayedOutcome -> {
                    return replayed(replayedOutcome.appointment());
                }
                case LostRace lostRace -> {
                    this.metrics.lostRace();
                    log.warn("Booking attempt {} lost a race on {}; retrying on fresh data", attemptNumber,
                            lostRace.constraint());
                }
            }
        }
        throw new ApiException(ErrorCode.SLOT_CONTENTION);
    }

    private static BookingResult replayed(AppointmentResponse appointment) {
        log.info("Booking replayed: appointment {}", appointment.id());
        return new BookingResult(appointment, true);
    }

    private BookingContext validate(BookAppointmentRequest request, Instant start, String key, String fingerprint) {
        Dealership dealership = this.dealerships.findById(request.dealershipId())
                .orElseThrow(() -> new ApiException(ErrorCode.DEALERSHIP_NOT_FOUND, request.dealershipId()));
        if (!this.customers.existsById(request.customerId())) {
            throw new ApiException(ErrorCode.CUSTOMER_NOT_FOUND, request.customerId());
        }
        Vehicle vehicle = this.vehicles.findById(request.vehicleId())
                .orElseThrow(() -> new ApiException(ErrorCode.VEHICLE_NOT_FOUND, request.vehicleId()));
        ServiceType serviceType = this.serviceTypes.findById(request.serviceTypeId())
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_TYPE_NOT_FOUND, request.serviceTypeId()));
        if (vehicle.getCustomerId() != request.customerId()) {
            throw new ApiException(ErrorCode.VEHICLE_NOT_OWNED_BY_CUSTOMER, request.vehicleId(), request.customerId());
        }

        TimeRange wanted = TimeRange.of(start, Duration.ofMinutes(serviceType.getDurationMinutes()));
        BusinessDay day = BusinessDay.containing(start, ZoneId.of(dealership.getTimeZone()), dealership.getOpens(),
                dealership.getCloses());
        Optional<StartTimeViolation> violation = StartTimeRule.check(wanted, this.clock.instant(), day,
                this.properties.slotStep());
        if (violation.isPresent()) {
            throw new ApiException(switch (violation.get()) {
                case IN_PAST -> ErrorCode.START_TIME_IN_PAST;
                case OUTSIDE_OPENING_HOURS -> ErrorCode.OUTSIDE_OPENING_HOURS;
                case NOT_ON_GRID -> ErrorCode.START_TIME_NOT_ON_GRID;
            });
        }
        return new BookingContext(request.dealershipId(), request.customerId(), request.vehicleId(),
                request.serviceTypeId(), day, wanted, key, fingerprint);
    }
}
