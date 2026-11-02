package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;

/** appointment-booking spec: booking, allocation, workload balancing, time zones, one vehicle at a time. */
class AppointmentBookingIntegrationTest extends AppointmentApiTestSupport {

    private long emma;
    private long golf;
    private long otherCustomer;
    private long otherVehicle;

    @BeforeEach
    void createCustomers() {
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
        otherCustomer = fixtures.customer("Duc");
        otherVehicle = fixtures.vehicle(otherCustomer);
    }

    @Nested
    @DisplayName("Book a service appointment")
    class BookAServiceAppointment {

        @Test
        @DisplayName("Successful booking")
        void successfulBooking() {
            Workshop workshop = london(60, 1, 1);

            book(workshop.request(emma, golf, "2026-11-03T10:00:00Z"))
                    .expectStatus().isCreated()
                    .expectHeader().contentType(APPLICATION_JSON)
                    .expectHeader().value("Location", location -> assertThat(location).matches(APPOINTMENTS + "/[0-9a-f-]{36}"))
                    .expectHeader().doesNotExist("Idempotent-Replayed")
                    .expectBody()
                    .jsonPath("$.id").isNotEmpty()
                    .jsonPath("$.status").isEqualTo("CONFIRMED")
                    .jsonPath("$.dealershipId").isEqualTo(workshop.dealershipId())
                    .jsonPath("$.customerId").isEqualTo(emma)
                    .jsonPath("$.vehicleId").isEqualTo(golf)
                    .jsonPath("$.serviceTypeId").isEqualTo(workshop.serviceTypeId())
                    .jsonPath("$.serviceBay.id").isEqualTo(workshop.bay(1))
                    .jsonPath("$.serviceBay.name").isEqualTo("Bay 1")
                    .jsonPath("$.technician.id").isEqualTo(workshop.technician(1))
                    .jsonPath("$.technician.name").isEqualTo("Technician 1")
                    .jsonPath("$.startTime").value((String time) ->
                            assertThat(OffsetDateTime.parse(time)).isEqualTo(OffsetDateTime.parse("2026-11-03T10:00:00Z")))
                    .jsonPath("$.endTime").value((String time) ->
                            assertThat(OffsetDateTime.parse(time)).isEqualTo(OffsetDateTime.parse("2026-11-03T11:00:00Z")))
                    .jsonPath("$.timeZone").isEqualTo("Europe/London");

            assertThat(jdbc.sql("SELECT status FROM appointment").query(String.class).single()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("End time derived from service duration")
        void endTimeDerivedFromServiceDuration() {
            Workshop workshop = saigon(90, 1, 1);

            book(workshop.request(emma, golf, "2026-11-03T10:00:00+07:00"))
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.startTime").isEqualTo("2026-11-03T10:00:00+07:00")
                    .jsonPath("$.endTime").isEqualTo("2026-11-03T11:30:00+07:00");
        }
    }

    @Nested
    @DisplayName("Bay and qualified technician available for the whole duration")
    class BayAndTechnicianAvailable {

        @Test
        @DisplayName("Resource busy for part of the period")
        void resourceBusyForPartOfThePeriod() {
            Workshop workshop = london(60, 1, 2);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 1, 2, at("10:45"), at("11:15")));

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
            assertThat(appointmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Bays free but no qualified technician free")
        void baysFreeButNoQualifiedTechnicianFree() {
            Workshop workshop = london(60, 2, 1);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 2, 1, at("10:00"), at("11:00")));

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
        }

        @Test
        @DisplayName("Unqualified or inactive technicians are never allocated")
        void unqualifiedOrInactiveTechniciansAreNeverAllocated() {
            Workshop workshop = london(60, 1, 0);
            long inactive = fixtures.technician(workshop.dealershipId(), "Inactive", false);
            fixtures.skill(inactive, workshop.serviceTypeId());
            long retired = fixtures.technician(workshop.dealershipId(), "Retired skill");
            fixtures.skill(retired, workshop.serviceTypeId(), false);
            fixtures.technician(workshop.dealershipId(), "Unqualified");

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
        }

        @Test
        @DisplayName("Inactive bays are never allocated")
        void inactiveBaysAreNeverAllocated() {
            Workshop workshop = london(60, 0, 1);
            fixtures.bay(workshop.dealershipId(), "Closed bay", false);

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
        }

        @Test
        @DisplayName("Resources of another dealership are never allocated")
        void resourcesOfAnotherDealershipAreNeverAllocated() {
            Workshop workshop = london(60, 1, 1);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 1, 1, at("10:00"), at("11:00")));
            long leeds = fixtures.dealership("Leeds");
            fixtures.bay(leeds, "Leeds bay");
            long leedsTechnician = fixtures.technician(leeds, "Leeds technician");
            fixtures.skill(leedsTechnician, workshop.serviceTypeId());

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
        }

        @Test
        @DisplayName("Back-to-back bookings share resources")
        void backToBackBookingsShareResources() {
            Workshop workshop = london(60, 1, 1);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 1, 1, at("09:00"), at("10:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

            assertThat(idOf(appointment, "serviceBay")).isEqualTo(workshop.bay(1));
            assertThat(idOf(appointment, "technician")).isEqualTo(workshop.technician(1));
        }

        @Test
        @DisplayName("Cancelled appointments do not occupy resources")
        void cancelledAppointmentsDoNotOccupyResources() {
            Workshop workshop = london(60, 1, 1);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 1, 1, at("10:00"), at("11:00"))
                    .withStatus("CANCELLED"));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

            assertThat(idOf(appointment, "serviceBay")).isEqualTo(workshop.bay(1));
        }
    }

    @Nested
    @DisplayName("Technician workload balancing")
    class TechnicianWorkloadBalancing {

        @Test
        @DisplayName("Lighter day wins")
        void lighterDayWins() {
            Workshop workshop = london(60, 3, 2);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 2, 1, at("08:00"), at("11:00")));
            fixtures.appointment(workshop.appointment(otherCustomer, fixtures.vehicle(otherCustomer), 3, 2, at("12:00"), at("13:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T14:00:00Z"));

            assertThat(idOf(appointment, "technician")).isEqualTo(workshop.technician(2));
        }

        @Test
        @DisplayName("Cancelled work does not count")
        void cancelledWorkDoesNotCount() {
            Workshop workshop = london(60, 3, 2);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 2, 1, at("08:00"), at("11:00"))
                    .withStatus("CANCELLED"));
            fixtures.appointment(workshop.appointment(otherCustomer, fixtures.vehicle(otherCustomer), 3, 2, at("12:00"), at("13:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T14:00:00Z"));

            assertThat(idOf(appointment, "technician")).isEqualTo(workshop.technician(1));
        }

        @Test
        @DisplayName("Ties broken by technician id")
        void tiesBrokenByTechnicianId() {
            Workshop workshop = london(60, 3, 2);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 2, 2, at("08:00"), at("09:00")));
            fixtures.appointment(workshop.appointment(otherCustomer, fixtures.vehicle(otherCustomer), 3, 1, at("12:00"), at("13:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T14:00:00Z"));

            assertThat(idOf(appointment, "technician")).isEqualTo(workshop.technician(1));
        }

        @Test
        @DisplayName("Lowest free bay wins")
        void lowestFreeBayWins() {
            Workshop workshop = london(60, 3, 3);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 2, 3, at("10:00"), at("11:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

            assertThat(idOf(appointment, "serviceBay")).isEqualTo(workshop.bay(1));
        }

        @Test
        @DisplayName("Lowest free bay wins when bay 1 is busy")
        void nextBayWinsWhenTheFirstIsBusy() {
            Workshop workshop = london(60, 3, 3);
            fixtures.appointment(workshop.appointment(otherCustomer, otherVehicle, 1, 3, at("10:00"), at("11:00")));

            Map<String, Object> appointment = booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

            assertThat(idOf(appointment, "serviceBay")).isEqualTo(workshop.bay(2));
        }
    }

    @Nested
    @DisplayName("Booking times in the dealership's time zone")
    class BookingTimesInTheDealershipsTimeZone {

        @Test
        @DisplayName("Client in another offset")
        void clientInAnotherOffset() {
            Workshop workshop = saigon(60, 1, 1);

            book(workshop.request(emma, golf, "2026-11-03T08:45:00+05:45"))
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.startTime").isEqualTo("2026-11-03T10:00:00+07:00")
                    .jsonPath("$.endTime").isEqualTo("2026-11-03T11:00:00+07:00")
                    .jsonPath("$.timeZone").isEqualTo("Asia/Ho_Chi_Minh");
        }

        @Test
        @DisplayName("Grid evaluated at the dealership")
        void gridEvaluatedAtTheDealership() {
            Workshop workshop = saigon(60, 1, 1);

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00+05:45")), 422, "START_TIME_NOT_ON_GRID");
        }

        @Test
        @DisplayName("Opening hours follow daylight saving time")
        void openingHoursFollowDaylightSavingTime() {
            clock.set(Instant.parse("2026-03-28T00:00:00Z"));
            Workshop workshop = london(60, 1, 1);

            problem(book(workshop.request(emma, golf, "2026-03-29T17:00:00Z")), 422, "OUTSIDE_OPENING_HOURS");
        }

        @Test
        @DisplayName("Booking on a daylight saving day")
        void bookingOnADaylightSavingDay() {
            clock.set(Instant.parse("2026-03-28T00:00:00Z"));
            Workshop workshop = london(60, 1, 1);

            book(workshop.request(emma, golf, "2026-03-29T17:00:00+01:00"))
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.startTime").isEqualTo("2026-03-29T17:00:00+01:00")
                    .jsonPath("$.endTime").isEqualTo("2026-03-29T18:00:00+01:00");
        }
    }

    @Nested
    @DisplayName("One booking per vehicle at a time")
    class OneBookingPerVehicleAtATime {

        @Test
        @DisplayName("Same vehicle at the same time")
        void sameVehicleAtTheSameTime() {
            Workshop workshop = london(60, 2, 2);
            booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

            problem(book(workshop.request(emma, golf, "2026-11-03T10:30:00Z")), 409, "VEHICLE_ALREADY_BOOKED");
            assertThat(appointmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Same instant at dealerships in different time zones")
        void sameInstantAtDealershipsInDifferentTimeZones() {
            Workshop london = london(60, 1, 1);
            Workshop saigon = saigon(45, 1, 1);
            booked(london.request(emma, golf, "2026-11-03T09:30:00Z"));

            problem(book(saigon.request(emma, golf, "2026-11-03T16:30:00+07:00")), 409, "VEHICLE_ALREADY_BOOKED");
        }

        @Test
        @DisplayName("Same clock time is not the same instant")
        void sameClockTimeIsNotTheSameInstant() {
            Workshop london = london(60, 1, 1);
            Workshop saigon = saigon(45, 1, 1);
            booked(london.request(emma, golf, "2026-11-03T09:30:00Z"));

            booked(saigon.request(emma, golf, "2026-11-03T09:30:00+07:00"));
            assertThat(appointmentCount()).isEqualTo(2);
        }
    }

    /** A time on 3 November 2026 in London, where the offset is +00:00 in November. */
    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-03T" + localTime + ":00Z");
    }
}
