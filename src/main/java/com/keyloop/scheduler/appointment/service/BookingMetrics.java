package com.keyloop.scheduler.appointment.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.keyloop.scheduler.web.error.ErrorCode;

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

    void failed() {
        this.outcomes.get("error").increment();
    }

    void lostRace() {
        this.conflicts.increment();
    }
}
