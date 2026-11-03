package com.keyloop.scheduler.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
