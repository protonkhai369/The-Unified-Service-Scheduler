package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

class AppointmentListIntegrationTest extends AppointmentApiTestSupport {

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

    @Nested
    @DisplayName("Caller's appointments")
    class CallersAppointments {

        @Test
        @DisplayName("Customer sees only their own appointments")
        void customerSeesOnlyTheirOwnAppointments() {
            Object inLondon = booked(london.request(emma, golf, "2026-11-04T10:00:00Z")).get("id");
            Object inSaigon = booked(saigon.request(emma, golf, "2026-11-03T09:00:00+07:00")).get("id");
            booked(london.request(duc, focus, "2026-11-04T12:00:00Z"));

            List<Map<String, Object>> items = items(myAppointments(tokens.customer(emma)).expectStatus().isOk());

            assertThat(items).extracting(item -> item.get("id")).containsExactly(inSaigon, inLondon);
            assertThat(items).extracting(item -> item.get("startTime"))
                    .containsExactly("2026-11-03T09:00:00+07:00", "2026-11-04T10:00:00Z");
        }

        @Test
        @DisplayName("Cancelled appointments stay in the list")
        void cancelledAppointmentsStayInTheList() {
            Object id = booked(london.request(emma, golf, "2026-11-04T10:00:00Z")).get("id");
            cancelAs(tokens.customer(emma), id).expectStatus().isOk();

            assertThat(items(myAppointments(tokens.customer(emma)).expectStatus().isOk()))
                    .singleElement()
                    .satisfies(item -> assertThat(item).containsEntry("id", id).containsEntry("status", "CANCELLED"));
        }

        @Test
        @DisplayName("Customer without appointments")
        void customerWithoutAppointments() {
            assertThat(items(myAppointments(tokens.customer(emma)).expectStatus().isOk())).isEmpty();
        }

        @Test
        @DisplayName("Staff have no appointments of their own")
        void staffHaveNoAppointmentsOfTheirOwn() {
            problem(myAppointments(tokens.advisor(saigon.dealershipId())), 403, "FORBIDDEN");
            problem(myAppointments(tokens.admin()), 403, "FORBIDDEN");
        }
    }

    @Nested
    @DisplayName("Dealership day schedule")
    class DealershipDaySchedule {

        @Test
        @DisplayName("Advisor sees their dealership's day")
        void advisorSeesTheirDealershipsDay() {
            Object late = booked(saigon.request(emma, golf, "2026-11-03T10:00:00+07:00")).get("id");
            Object early = booked(saigon.request(duc, focus, "2026-11-03T08:00:00+07:00")).get("id");
            booked(saigon.request(emma, golf, "2026-11-04T08:00:00+07:00"));

            List<Map<String, Object>> items = items(
                    schedule(tokens.advisor(saigon.dealershipId()), saigon.dealershipId(), "2026-11-03").expectStatus().isOk());

            assertThat(items).extracting(item -> item.get("id")).containsExactly(early, late);
        }

        @Test
        @DisplayName("Local day, not UTC day")
        void localDayNotUtcDay() {
            Workshop auckland = fixtures.workshop("Auckland", "Pacific/Auckland", LocalTime.of(8, 0), LocalTime.of(18, 0),
                    60, 1, 1);
            Map<String, Object> appointment = booked(auckland.request(emma, golf, "2026-11-03T08:00:00+13:00"));
            assertThat(appointment).containsEntry("startTime", "2026-11-03T08:00:00+13:00");

            assertThat(ids(schedule(tokens.admin(), auckland.dealershipId(), "2026-11-03")))
                    .containsExactly(appointment.get("id"));
            assertThat(ids(schedule(tokens.admin(), auckland.dealershipId(), "2026-11-02"))).isEmpty();
        }

        @Test
        @DisplayName("Other dealerships are not mixed in")
        void otherDealershipsAreNotMixedIn() {
            Object inSaigon = booked(saigon.request(emma, golf, "2026-11-03T10:00:00+07:00")).get("id");
            booked(london.request(duc, focus, "2026-11-03T10:00:00Z"));

            assertThat(ids(schedule(tokens.admin(), saigon.dealershipId(), "2026-11-03"))).containsExactly(inSaigon);
        }

        @Test
        @DisplayName("Cancelled appointments are left out")
        void cancelledAppointmentsAreLeftOut() {
            Object kept = booked(saigon.request(emma, golf, "2026-11-03T10:00:00+07:00")).get("id");
            Object cancelled = booked(saigon.request(duc, focus, "2026-11-03T12:00:00+07:00")).get("id");
            cancel(cancelled).expectStatus().isOk();

            assertThat(ids(schedule(tokens.admin(), saigon.dealershipId(), "2026-11-03"))).containsExactly(kept);
        }

        @Test
        @DisplayName("A confirmed booking is visible at once")
        void aConfirmedBookingIsVisibleAtOnce() {
            Object id = bookAs(tokens.advisor(saigon.dealershipId()), saigon.request(emma, golf, "2026-11-03T10:00:00+07:00"))
                    .expectStatus().isCreated()
                    .expectBody(JSON_OBJECT).returnResult().getResponseBody().get("id");

            assertThat(ids(schedule(tokens.advisor(saigon.dealershipId()), saigon.dealershipId(), "2026-11-03")))
                    .containsExactly(id);
        }

        @Test
        @DisplayName("Advisor of another dealership")
        void advisorOfAnotherDealership() {
            problem(schedule(tokens.advisor(london.dealershipId()), saigon.dealershipId(), "2026-11-03"), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("Customer requests a dealership's day")
        void customerRequestsADealershipsDay() {
            problem(schedule(tokens.customer(emma), saigon.dealershipId(), "2026-11-03"), 403, "FORBIDDEN");
        }

        @Test
        @DisplayName("Admin and unknown dealership")
        void adminAndUnknownDealership() {
            problem(schedule(tokens.admin(), 999, "2026-11-03"), 404, "DEALERSHIP_NOT_FOUND");
        }

        @Test
        @DisplayName("Missing date")
        void missingDate() {
            problem(http.get().uri("/api/v1/dealerships/{id}/appointments", saigon.dealershipId())
                    .headers(headers -> headers.setBearerAuth(tokens.admin())).exchange(), 400, "VALIDATION_FAILED")
                    .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly("date"));
        }

        @Test
        @DisplayName("Schedule without a token")
        void scheduleWithoutToken() {
            problem(http.get().uri("/api/v1/dealerships/{id}/appointments?date=2026-11-03", saigon.dealershipId())
                    .exchange(), 401, "UNAUTHENTICATED");
        }
    }

    private ResponseSpec myAppointments(String token) {
        return http.get().uri("/api/v1/me/appointments").headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private ResponseSpec schedule(String token, long dealershipId, String date) {
        return http.get().uri("/api/v1/dealerships/{id}/appointments?date={date}", dealershipId, date)
                .headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private static List<Object> ids(ResponseSpec response) {
        return items(response.expectStatus().isOk()).stream().map(item -> item.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseSpec response) {
        return (List<Map<String, Object>>) response.expectBody(JSON_OBJECT).returnResult().getResponseBody().get("items");
    }
}
