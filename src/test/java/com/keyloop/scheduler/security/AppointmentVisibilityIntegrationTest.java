package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/**
 * access-control spec, "Appointment visibility", and the reading scenarios of "Roles from the token".
 * Emma has one appointment at the London dealership; Duc is another customer.
 */
@DisplayName("Appointment visibility")
class AppointmentVisibilityIntegrationTest extends AppointmentApiTestSupport {

    private Workshop london;
    private Workshop saigon;
    private long emma;
    private long duc;
    private Object appointment;

    @BeforeEach
    void bookEmmasAppointment() {
        london = london(60, 1, 1);
        saigon = saigon(60, 1, 1);
        emma = fixtures.customer("Emma");
        duc = fixtures.customer("Duc");
        appointment = booked(london.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z")).get("id");
    }

    @Test
    @DisplayName("Customer reads their appointment")
    void customerReadsTheirAppointment() {
        getAs(tokens.customer(emma), appointment).expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(appointment);
    }

    @Test
    @DisplayName("Customer reads another customer's appointment")
    void customerReadsAnotherCustomersAppointment() {
        problem(getAs(tokens.customer(duc), appointment), 404, "APPOINTMENT_NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("Appointment " + appointment + " does not exist.");
    }

    @Test
    void anotherCustomersAppointmentLooksLikeAnUnknownId() {
        UUID unknown = UUID.fromString("6f1c0a52-0000-4000-8000-000000000000");

        problem(getAs(tokens.customer(duc), unknown), 404, "APPOINTMENT_NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("Appointment " + unknown + " does not exist.");
    }

    @Test
    @DisplayName("Customer cannot cancel another customer's appointment")
    void customerCannotCancelAnotherCustomersAppointment() {
        problem(cancelAs(tokens.customer(duc), appointment), 404, "APPOINTMENT_NOT_FOUND");

        assertThat(status(appointment)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Advisor cancels at their dealership")
    void advisorCancelsAtTheirDealership() {
        cancelAs(tokens.advisor(london.dealershipId()), appointment).expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void advisorCannotCancelAtAnotherDealership() {
        problem(cancelAs(tokens.advisor(saigon.dealershipId()), appointment), 404, "APPOINTMENT_NOT_FOUND");

        assertThat(status(appointment)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Advisor reads another dealership's appointment")
    void advisorReadsAnotherDealershipsAppointment() {
        problem(getAs(tokens.advisor(saigon.dealershipId()), appointment), 404, "APPOINTMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("Admin reads any appointment")
    void adminReadsAnyAppointment() {
        getAs(tokens.admin(), appointment).expectStatus().isOk();
    }

    @Test
    @DisplayName("Role claimed in user metadata is ignored")
    void roleClaimedInUserMetadataIsIgnored() {
        problem(getAs(tokens.adminInUserMetadata(), appointment), 403, "FORBIDDEN");
    }

    @Test
    void callerWithoutUsableRoleIsForbiddenOnEveryAppointmentEndpoint() {
        problem(getAs(tokens.withoutRole(), appointment), 403, "FORBIDDEN");
        problem(cancelAs(tokens.customerWithoutCustomerId(), appointment), 403, "FORBIDDEN");

        assertThat(status(appointment)).isEqualTo("CONFIRMED");
    }

    private String status(Object id) {
        return jdbc.sql("SELECT status FROM appointment WHERE id = ?::uuid").param(id.toString()).query(String.class)
                .single();
    }
}
