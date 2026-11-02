package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

/**
 * appointment-booking spec, "Booking request validation", and the error-responses scenario
 * "Several invalid fields". Every rejected request must leave the appointment table empty.
 */
@DisplayName("Booking request validation")
class BookingValidationIntegrationTest extends AppointmentApiTestSupport {

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
    void nothingWasPersisted() {
        assertThat(appointmentCount()).isZero();
    }

    @Test
    @DisplayName("Missing field")
    void missingField() {
        Map<String, Object> body = valid();
        body.remove("serviceTypeId");

        problem(book(body), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("serviceTypeId"));
    }

    @Test
    @DisplayName("Several invalid fields")
    void severalInvalidFields() {
        Map<String, Object> body = valid();
        body.remove("vehicleId");
        body.put("dealershipId", -1);

        problem(book(body), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) ->
                        assertThat(fields).containsExactlyInAnyOrder("vehicleId", "dealershipId"));
    }

    @Test
    @DisplayName("Start time without an offset")
    void startTimeWithoutAnOffset() {
        problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00")), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("startTime"));
    }

    @Test
    @DisplayName("Malformed idempotency key")
    void malformedIdempotencyKey() {
        problem(book(valid(), "has space"), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("Idempotency-Key"));
        problem(book(valid(), "k".repeat(101)), 400, "VALIDATION_FAILED")
                .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("Idempotency-Key"));
    }

    @Test
    @DisplayName("Unknown dealership")
    void unknownDealership() {
        Map<String, Object> body = valid();
        body.put("dealershipId", 999);

        problem(book(body), 404, "DEALERSHIP_NOT_FOUND");
    }

    @Test
    @DisplayName("Unknown customer")
    void unknownCustomer() {
        Map<String, Object> body = valid();
        body.put("customerId", 999);

        problem(book(body), 404, "CUSTOMER_NOT_FOUND");
    }

    @Test
    @DisplayName("Unknown vehicle")
    void unknownVehicle() {
        Map<String, Object> body = valid();
        body.put("vehicleId", 999);

        problem(book(body), 404, "VEHICLE_NOT_FOUND");
    }

    @Test
    @DisplayName("Unknown service type")
    void unknownServiceType() {
        Map<String, Object> body = valid();
        body.put("serviceTypeId", 999);

        problem(book(body), 404, "SERVICE_TYPE_NOT_FOUND");
    }

    @Test
    @DisplayName("Vehicle belongs to another customer")
    void vehicleBelongsToAnotherCustomer() {
        long duc = fixtures.customer("Duc");

        problem(book(workshop.request(duc, golf, "2026-11-03T10:00:00Z")), 422, "VEHICLE_NOT_OWNED_BY_CUSTOMER");
    }

    @Test
    @DisplayName("Start time in the past")
    void startTimeInThePast() {
        clock.set(Instant.parse("2026-11-03T10:00:00Z"));

        problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 422, "START_TIME_IN_PAST");
        problem(book(workshop.request(emma, golf, "2026-11-03T09:30:00Z")), 422, "START_TIME_IN_PAST");
    }

    @Test
    @DisplayName("Service would run past closing time")
    void serviceWouldRunPastClosingTime() {
        Workshop longService = fixtures.workshop("Leeds", "Europe/London", LocalTime.of(8, 0),
                LocalTime.of(18, 0), 90, 1, 1);

        problem(book(longService.request(emma, golf, "2026-11-03T17:00:00Z")), 422, "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Start before opening time")
    void startBeforeOpeningTime() {
        problem(book(workshop.request(emma, golf, "2026-11-03T07:00:00Z")), 422, "OUTSIDE_OPENING_HOURS");
    }

    @Test
    @DisplayName("Start time off the slot grid")
    void startTimeOffTheSlotGrid() {
        problem(book(workshop.request(emma, golf, "2026-11-03T10:15:00Z")), 422, "START_TIME_NOT_ON_GRID");
    }

    @Test
    @DisplayName("Unknown references are reported before start-time rules")
    void unknownReferenceIsReportedBeforeStartTimeRules() {
        clock.set(Instant.parse("2026-11-04T00:00:00Z"));
        Map<String, Object> body = valid();
        body.put("customerId", 999);

        problem(book(body), 404, "CUSTOMER_NOT_FOUND");
    }

    private Map<String, Object> valid() {
        return workshop.request(emma, golf, "2026-11-03T10:00:00Z");
    }
}
