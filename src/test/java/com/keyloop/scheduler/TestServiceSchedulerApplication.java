package com.keyloop.scheduler;

import org.springframework.boot.SpringApplication;

/** Runs the application against a Testcontainers PostgreSQL instead of the configured database. */
public class TestServiceSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.from(ServiceSchedulerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
