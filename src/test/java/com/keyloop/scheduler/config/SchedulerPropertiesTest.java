package com.keyloop.scheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SchedulerPropertiesTest {

    @Test
    void defaultsApplyWithoutConfiguration() {
        SchedulerProperties properties = bind(Map.of());

        assertThat(properties.maxBookingAttempts()).isEqualTo(5);
        assertThat(properties.slotStep()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.bookingLockTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void bindsRelaxedNames() {
        SchedulerProperties properties = bind(Map.of("scheduler.max-booking-attempts", "2",
                "scheduler.slot-step", "15m", "scheduler.booking-lock-timeout", "500ms"));

        assertThat(properties.maxBookingAttempts()).isEqualTo(2);
        assertThat(properties.slotStep()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.bookingLockTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void rejectsValuesThatCannotWork() {
        assertThatThrownBy(() -> bind(Map.of("scheduler.max-booking-attempts", "0"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("scheduler.slot-step", "0m"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("scheduler.slot-step", "90s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("scheduler.booking-lock-timeout", "0s"))).isInstanceOf(BindException.class);
    }

    private static SchedulerProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("scheduler", SchedulerProperties.class);
    }
}
