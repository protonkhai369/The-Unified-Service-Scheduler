package com.keyloop.scheduler;

import org.springframework.boot.SpringApplication;

public class TestServiceSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.from(ServiceSchedulerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
