package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

/** appointment-booking spec, "Appointment cancellation". */
@DisplayName("Appointment cancellation")
@ExtendWith(OutputCaptureExtension.class)
class AppointmentCancellationIntegrationTest extends AppointmentApiTestSupport {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private Workshop workshop;
    private long emma;
    private long golf;

    @BeforeEach
    void createWorkshop() {
        workshop = london(60, 1, 1);
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Cancellation frees the slot")
    void cancellationFreesTheSlot(CapturedOutput output) {
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z")).get("id");

        cancel(id).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.status").isEqualTo("CANCELLED");

        Map<String, Object> next = booked(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z"));
        assertThat(idOf(next, "serviceBay")).isEqualTo(workshop.bay(1));
        assertThat(idOf(next, "technician")).isEqualTo(workshop.technician(1));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(output.getOut()).contains("Appointment " + id + " cancelled"));
    }

    @Test
    @DisplayName("Cancel twice")
    void cancelTwice() {
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z")).get("id");
        cancel(id).expectStatus().isOk();
        String afterFirst = versionAndUpdatedAt();

        cancel(id).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");

        assertThat(versionAndUpdatedAt()).as("unchanged").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("Cancel after the start time")
    void cancelAfterTheStartTime() {
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z")).get("id");
        clock.set(Instant.parse("2026-11-03T10:00:00Z"));

        problem(cancel(id), 409, "APPOINTMENT_ALREADY_STARTED");

        assertThat(jdbc.sql("SELECT status FROM appointment").query(String.class).single()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Concurrent cancellations")
    void concurrentCancellations() throws Exception {
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z")).get("id");
        CountDownLatch start = new CountDownLatch(1);

        List<Future<EntityExchangeResult<Map<String, Object>>>> pending = List.of(
                pool.submit(() -> {
                    start.await();
                    return cancel(id).returnResult(JSON_OBJECT);
                }),
                pool.submit(() -> {
                    start.await();
                    return cancel(id).returnResult(JSON_OBJECT);
                }));
        start.countDown();

        for (Future<EntityExchangeResult<Map<String, Object>>> result : pending) {
            EntityExchangeResult<Map<String, Object>> reply = result.get(10, TimeUnit.SECONDS);
            assertThat(reply.getStatus().value()).isEqualTo(200);
            assertThat(reply.getResponseBody()).containsEntry("status", "CANCELLED");
        }
        assertThat(versionAndUpdatedAt()).startsWith("1|");
    }

    @Test
    @DisplayName("Cancel an unknown appointment")
    void cancelAnUnknownAppointment() {
        problem(cancel("6f1c0a52-0000-4000-8000-000000000000"), 404, "APPOINTMENT_NOT_FOUND");
    }

    private String versionAndUpdatedAt() {
        return jdbc.sql("SELECT version || '|' || updated_at FROM appointment").query(String.class).single();
    }
}
