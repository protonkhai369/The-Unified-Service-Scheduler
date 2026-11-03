package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

@DisplayName("Idempotent booking retries")
class IdempotencyIntegrationTest extends AppointmentApiTestSupport {

    private long emma;
    private long golf;

    @BeforeEach
    void createCustomer() {
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
    }

    @Test
    @DisplayName("Safe retry after a timeout")
    void safeRetryAfterATimeout() {
        Workshop workshop = london(60, 1, 1);
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");
        Object id = booked(request, "retry-1").get("id");

        book(request, "retry-1")
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectHeader().valueEquals("Location", APPOINTMENTS + "/" + id)
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.status").isEqualTo("CONFIRMED");
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Same instant in another offset")
    void sameInstantInAnotherOffset() {
        Workshop workshop = saigon(60, 1, 1);
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00+07:00"), "offset-1").get("id");

        book(workshop.request(emma, golf, "2026-11-03T03:00:00Z"), "offset-1")
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody()
                .jsonPath("$.id").isEqualTo(id);
    }

    @Test
    @DisplayName("Retry after the start time has passed")
    void retryAfterTheStartTimeHasPassed() {
        Workshop workshop = london(60, 1, 1);
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");
        Object id = booked(request, "late-1").get("id");
        clock.set(Instant.parse("2026-11-03T10:30:00Z"));

        book(request, "late-1")
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody()
                .jsonPath("$.id").isEqualTo(id);
    }

    @Test
    @DisplayName("Replay of a cancelled appointment")
    void replayOfACancelledAppointment() {
        Workshop workshop = london(60, 1, 1);
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");
        Object id = booked(request, "cancelled-1").get("id");
        jdbc.sql("UPDATE appointment SET status = 'CANCELLED'").update();

        book(request, "cancelled-1")
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.status").isEqualTo("CANCELLED");
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Key reused for a different booking")
    void keyReusedForADifferentBooking() {
        Workshop workshop = london(60, 1, 1);
        booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"), "reused-1");

        problem(book(workshop.request(emma, golf, "2026-11-03T11:00:00Z"), "reused-1"), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Requests without a key are not deduplicated")
    void requestsWithoutAKeyAreNotDeduplicated() {
        Workshop workshop = london(60, 2, 2);
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");
        booked(request);

        problem(book(request), 409, "VEHICLE_ALREADY_BOOKED");
        assertThat(jdbc.sql("SELECT idempotency_key FROM appointment").query(String.class).single())
                .as("a server-generated key")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
