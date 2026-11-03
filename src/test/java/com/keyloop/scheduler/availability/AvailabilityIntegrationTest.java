package com.keyloop.scheduler.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

class AvailabilityIntegrationTest extends AppointmentApiTestSupport {

    @Nested
    @DisplayName("List bookable start times for a day")
    class ListBookableStartTimes {

        @Test
        @DisplayName("Response describes the context")
        void responseDescribesTheContext() {
            Workshop workshop = saigon(60, 1, 1);

            availability(workshop.dealershipId(), workshop.serviceTypeId(), "2026-11-03")
                    .expectStatus().isOk()
                    .expectHeader().contentType(APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$.dealershipId").isEqualTo(workshop.dealershipId())
                    .jsonPath("$.serviceTypeId").isEqualTo(workshop.serviceTypeId())
                    .jsonPath("$.date").isEqualTo("2026-11-03")
                    .jsonPath("$.timeZone").isEqualTo("Asia/Ho_Chi_Minh")
                    .jsonPath("$.durationMinutes").isEqualTo(60)
                    .jsonPath("$.slots[0].startTime").isEqualTo("2026-11-03T07:30:00+07:00")
                    .jsonPath("$.slots[0].endTime").isEqualTo("2026-11-03T08:30:00+07:00")
                    .jsonPath("$.slots[0].availableBays").isEqualTo(1)
                    .jsonPath("$.slots[0].availableTechnicians").isEqualTo(1);
        }

        @Test
        @DisplayName("Times in the dealership's offset")
        void timesInTheDealershipsOffset() {
            Workshop workshop = saigon(60, 1, 1);
            long emma = fixtures.customer("Emma");
            long golf = fixtures.vehicle(emma);

            Map<String, Object> slot = slots(workshop, "2026-11-03").stream()
                    .filter(candidate -> "2026-11-03T10:00:00+07:00".equals(candidate.get("startTime")))
                    .findFirst()
                    .orElseThrow();

            assertThat(slot).containsEntry("endTime", "2026-11-03T11:00:00+07:00");
            book(workshop.request(emma, golf, (String) slot.get("startTime"))).expectStatus().isCreated();
        }
    }

    @Nested
    @DisplayName("Slots require a free bay and a free qualified technician")
    class FreeBayAndQualifiedTechnician {

        @Test
        @DisplayName("Unqualified, inactive and foreign resources are not counted")
        void unqualifiedInactiveAndForeignResourcesAreNotCounted() {
            long dealership = fixtures.dealership("London", "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0));
            long oilChange = fixtures.serviceType("OIL_CHANGE", 60);
            fixtures.bay(dealership, "Bay 1");
            fixtures.bay(dealership, "Closed bay", false);
            fixtures.skill(fixtures.technician(dealership, "Qualified"), oilChange);
            fixtures.skill(fixtures.technician(dealership, "On leave", false), oilChange);
            fixtures.technician(dealership, "Unqualified");
            fixtures.skill(fixtures.technician(dealership, "Retired skill"), oilChange, false);
            long other = fixtures.dealership("Elsewhere", "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0));
            fixtures.bay(other, "Their bay");
            fixtures.skill(fixtures.technician(other, "Their technician"), oilChange);

            assertThat(slots(dealership, oilChange, "2026-11-03"))
                    .hasSize(19)
                    .allSatisfy(slot -> assertThat(slot)
                            .containsEntry("availableBays", 1)
                            .containsEntry("availableTechnicians", 1));
        }

        @Test
        @DisplayName("Cancelled appointments do not occupy resources")
        void cancelledAppointmentsDoNotOccupyResources() {
            Workshop workshop = london(60, 1, 1);
            long emma = fixtures.customer("Emma");
            fixtures.appointment(workshop.appointment(emma, fixtures.vehicle(emma), 1, 1, at("10:00"), at("11:00"))
                    .withStatus("CANCELLED"));

            assertThat(slots(workshop, "2026-11-03")).contains(Map.of(
                    "startTime", "2026-11-03T10:00:00Z", "endTime", "2026-11-03T11:00:00Z",
                    "availableBays", 1, "availableTechnicians", 1));
        }
    }

    @Nested
    @DisplayName("No past start times")
    class NoPastStartTimes {

        @Test
        @DisplayName("Date in the past")
        void dateInThePast() {
            Workshop workshop = london(60, 1, 1);

            availability(workshop.dealershipId(), workshop.serviceTypeId(), "2026-11-01")
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.date").isEqualTo("2026-11-01")
                    .jsonPath("$.slots").isEmpty();
        }

        @Test
        @DisplayName("Today is the dealership's date")
        void todayIsTheDealershipsDate() {
            Workshop workshop = fixtures.workshop("Saigon", "Asia/Ho_Chi_Minh", LocalTime.of(8, 0),
                    LocalTime.of(17, 30), 60, 1, 1);
            clock.set(Instant.parse("2026-11-02T20:00:00Z"));

            assertThat(startTimes(slots(workshop, "2026-11-03"))).first().isEqualTo("2026-11-03T08:00:00+07:00");
            assertThat(slots(workshop, "2026-11-02")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Availability query validation")
    class AvailabilityQueryValidation {

        @Test
        @DisplayName("Unknown dealership")
        void unknownDealership() {
            Workshop workshop = london(60, 1, 1);

            problem(availability(999, workshop.serviceTypeId(), "2026-11-03"), 404, "DEALERSHIP_NOT_FOUND");
        }

        @Test
        @DisplayName("Unknown service type")
        void unknownServiceType() {
            Workshop workshop = london(60, 1, 1);

            problem(availability(workshop.dealershipId(), 999, "2026-11-03"), 404, "SERVICE_TYPE_NOT_FOUND");
        }

        @Test
        @DisplayName("Unknown dealership and service type")
        void unknownDealershipAndServiceType() {
            problem(availability(999, 999, "2026-11-03"), 404, "DEALERSHIP_NOT_FOUND");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "Missing service type      | date=2026-11-03                 | serviceTypeId",
                "Non-positive service type | serviceTypeId=0&date=2026-11-03 | serviceTypeId",
                "Malformed date            | serviceTypeId=1&date=02/11/2026 | date" })
        void rejectsAMissingOrMalformedParameter(String scenario, String query, String field) {
            problem(http.get().uri("/api/v1/dealerships/1/availability?" + query)
                    .headers(headers -> headers.setBearerAuth(tokens.admin())).exchange(), 400, "VALIDATION_FAILED")
                    .jsonPath("$.errors[*].field").value((List<String> fields) -> assertThat(fields).containsExactly(field));
        }
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-03T" + localTime + ":00Z");
    }
}
