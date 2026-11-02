package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

/** appointment-booking spec, "Confirmed appointment record". */
@DisplayName("Confirmed appointment record")
class AppointmentQueryIntegrationTest extends AppointmentApiTestSupport {

    private Workshop workshop;
    private long emma;
    private long golf;

    @BeforeEach
    void createWorkshop() {
        workshop = saigon(90, 1, 1);
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
    }

    @Test
    @DisplayName("Retrieve a booked appointment")
    void retrieveABookedAppointment() {
        Map<String, Object> booked = booked(workshop.request(emma, golf, "2026-11-03T10:00:00+07:00"));

        Map<String, Object> retrieved = get(booked.get("id")).expectStatus().isOk()
                .expectBody(JSON_OBJECT).returnResult().getResponseBody();

        assertThat(retrieved).isEqualTo(booked);
        assertThat(retrieved).containsEntry("startTime", "2026-11-03T10:00:00+07:00")
                .containsEntry("endTime", "2026-11-03T11:30:00+07:00");
    }

    @Test
    @DisplayName("Retrieve a cancelled appointment")
    void retrieveACancelledAppointment() {
        Object id = booked(workshop.request(emma, golf, "2026-11-03T10:00:00+07:00")).get("id");
        jdbc.sql("UPDATE appointment SET status = 'CANCELLED'").update();

        get(id).expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Unknown appointment")
    void unknownAppointment() {
        UUID unknown = UUID.fromString("6f1c0a52-0000-4000-8000-000000000000");

        problem(get(unknown), 404, "APPOINTMENT_NOT_FOUND")
                .jsonPath("$.instance").isEqualTo(APPOINTMENTS + "/" + unknown);
    }

    @Test
    @DisplayName("Malformed appointment id")
    void malformedAppointmentId() {
        problem(get("abc"), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("id"));
    }
}
