package com.keyloop.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/**
 * access-control spec, "Booking permissions", and the booking scenarios of "Roles from the token".
 * Emma owns the Golf, Duc owns the Focus; London and Saigon are two dealerships.
 */
@DisplayName("Booking permissions")
class BookingPermissionIntegrationTest extends AppointmentApiTestSupport {

    @Autowired
    private MeterRegistry meterRegistry;

    private Workshop london;
    private Workshop saigon;
    private long emma;
    private long golf;
    private long duc;
    private long focus;

    @BeforeEach
    void createWorkshopsAndCustomers() {
        london = london(60, 1, 1);
        saigon = saigon(60, 1, 1);
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
        duc = fixtures.customer("Duc");
        focus = fixtures.vehicle(duc);
    }

    @Test
    @DisplayName("Customer books for themselves")
    void customerBooksForThemselves() {
        bookAs(tokens.customer(duc), london.request(duc, focus, "2026-11-03T10:00:00Z"))
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.customerId").isEqualTo(duc);
    }

    @Test
    @DisplayName("Customer books for someone else")
    void customerBooksForSomeoneElse() {
        double rejected = rejectedBookings();

        forbidden(bookAs(tokens.customer(duc), london.request(emma, golf, "2026-11-03T10:00:00Z")));

        assertThat(appointmentCount()).isZero();
        assertThat(rejectedBookings()).as("counted like every booking error code").isEqualTo(rejected + 1);
    }

    @Test
    @DisplayName("Advisor books at their dealership")
    void advisorBooksAtTheirDealership() {
        bookAs(tokens.advisor(saigon.dealershipId()), saigon.request(emma, golf, "2026-11-03T10:00:00+07:00"))
                .expectStatus().isCreated();
        bookAs(tokens.advisor(saigon.dealershipId()), saigon.request(duc, focus, "2026-11-03T12:00:00+07:00"))
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("Advisor books at another dealership")
    void advisorBooksAtAnotherDealership() {
        forbidden(bookAs(tokens.advisor(saigon.dealershipId()), london.request(duc, focus, "2026-11-03T10:00:00Z")));

        assertThat(appointmentCount()).isZero();
    }

    @Test
    @DisplayName("Forbidden request cannot replay another booking")
    void forbiddenRequestCannotReplayAnotherBooking() {
        Map<String, Object> request = london.request(emma, golf, "2026-11-03T10:00:00Z");
        bookAs(tokens.customer(emma), request, "k1").expectStatus().isCreated();

        forbidden(bookAs(tokens.customer(duc), request, "k1"))
                .jsonPath("$.id").doesNotExist();
    }

    @Test
    @DisplayName("Token without a role")
    void tokenWithoutRole() {
        forbidden(bookAs(tokens.withoutRole(), london.request(emma, golf, "2026-11-03T10:00:00Z")));

        assertThat(appointmentCount()).isZero();
    }

    @Test
    @DisplayName("Customer token without a customer id")
    void customerTokenWithoutCustomerId() {
        forbidden(bookAs(tokens.customerWithoutCustomerId(), london.request(emma, golf, "2026-11-03T10:00:00Z")));
    }

    @Test
    void permissionIsCheckedBeforeEveryBookingRule() {
        // In the past, off the grid and for an unknown dealership: still 403, not 404 or 422.
        clock.set(Instant.parse("2026-11-04T00:00:00Z"));
        Map<String, Object> request = new HashMap<>(london.request(emma, golf, "2026-11-03T10:15:00Z"));
        request.put("dealershipId", 999);

        forbidden(bookAs(tokens.customer(duc), request));
    }

    @Test
    void requestValidationComesFirst() {
        problem(bookAs(tokens.customer(duc), Map.of("dealershipId", -1)), 400, "VALIDATION_FAILED");
    }

    private static BodyContentSpec forbidden(ResponseSpec response) {
        return problem(response, 403, "FORBIDDEN");
    }

    private double rejectedBookings() {
        Counter counter = meterRegistry.find("scheduler.bookings").tag("outcome", "rejected").counter();
        return counter != null ? counter.count() : 0;
    }
}
