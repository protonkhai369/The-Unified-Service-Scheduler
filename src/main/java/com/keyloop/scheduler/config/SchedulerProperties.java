package com.keyloop.scheduler.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scheduler")
public record SchedulerProperties(@DefaultValue("5") int maxBookingAttempts,
        @DefaultValue("PT30M") Duration slotStep, @DefaultValue("PT3S") Duration bookingLockTimeout) {

    public SchedulerProperties {
        if (maxBookingAttempts < 1) {
            throw new IllegalArgumentException("scheduler.max-booking-attempts must be at least 1");
        }
        if (slotStep.toMinutes() < 1 || !slotStep.equals(Duration.ofMinutes(slotStep.toMinutes()))) {
            throw new IllegalArgumentException("scheduler.slot-step must be a positive number of whole minutes");
        }
        if (bookingLockTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("scheduler.booking-lock-timeout must be positive");
        }
    }
}
