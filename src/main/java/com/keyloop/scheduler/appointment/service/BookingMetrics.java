package com.keyloop.scheduler.appointment.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.keyloop.scheduler.web.error.ErrorCode;

/**
 * {@code scheduler_bookings_total{outcome}} and {@code scheduler_booking_conflicts_total}. Every series
 * is registered at startup, so dashboards see zeros rather than missing series. Requests rejected
 * with {@code 400} never reach the booking service and are counted only by
 * {@code http_server_requests_seconds}.
 */
@Component
class BookingMetrics {

    private final Map<String, Counter> outcomes;

    private final Counter conflicts;

    BookingMetrics(MeterRegistry registry) {
        this.outcomes = Stream.of("confirmed", "replayed", "no_availability", "contention", "rejected", "error")
                .collect(Collectors.toUnmodifiableMap(Function.identity(), outcome -> Counter.builder("scheduler.bookings")
                        .description("Booking requests that passed request validation, by outcome")
                        .tag("outcome", outcome)
                        .register(registry)));
        this.conflicts = Counter.builder("scheduler.booking.conflicts")
                .description("Allocation attempts that lost a race against a writer bypassing the booking lock")
                .register(registry);
    }

    void booked(BookingResult result) {
        this.outcomes.get(result.replayed() ? "replayed" : "confirmed").increment();
    }

    void rejected(ErrorCode code) {
        this.outcomes.get(switch (code) {
            case NO_AVAILABILITY -> "no_availability";
            case SLOT_CONTENTION -> "contention";
            default -> "rejected";
        }).increment();
    }

    /** An unexpected failure, answered with 500 INTERNAL_ERROR. */
    void failed() {
        this.outcomes.get("error").increment();
    }

    void lostRace() {
        this.conflicts.increment();
    }
}
