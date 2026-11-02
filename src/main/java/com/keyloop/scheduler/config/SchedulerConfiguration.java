package com.keyloop.scheduler.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Booking settings and the application's only source of "now". Code that compares against the
 * current time takes this {@link Clock}, so tests can move time (see the test clock in
 * {@code ProbeConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
