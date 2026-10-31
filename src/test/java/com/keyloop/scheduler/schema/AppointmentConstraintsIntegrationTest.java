package com.keyloop.scheduler.schema;

import static com.keyloop.scheduler.SqlAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.keyloop.scheduler.DatabaseFixtures.NewAppointment;
import com.keyloop.scheduler.DatabaseIntegrationTest;

/**
 * Spec: scheduling-data-model, requirements "Appointment record", the three overlap requirements,
 * "Qualified technician from the same dealership" and "Idempotency key storage".
 * Every row is inserted with plain SQL, so the database alone has to enforce the rule.
 */
class AppointmentConstraintsIntegrationTest extends DatabaseIntegrationTest {

    private long oilChange;
    private long brakeService;
    private long bay1;
    private long bay2;
    private long technician1;
    private long technician2;
    private long otherDealershipBay;
    private long otherDealershipTechnician;
    private long emma;
    private long emmasGolf;
    private long emmasPolo;
    private long duc;
    private long ducsFocus;

    /** Emma's Golf on bay 1 with technician 1, 10:00-11:00 at +07:00. */
    private NewAppointment booking;

    @BeforeEach
    void createWorkshop() {
        long dealership = fixtures.dealership("North");
        long otherDealership = fixtures.dealership("South");
        oilChange = fixtures.serviceType("OIL_CHANGE", 60);
        brakeService = fixtures.serviceType("BRAKE_SERVICE", 90);
        bay1 = fixtures.bay(dealership, "Bay 1");
        bay2 = fixtures.bay(dealership, "Bay 2");
        otherDealershipBay = fixtures.bay(otherDealership, "Bay 1");
        technician1 = fixtures.technician(dealership, "Alice");
        technician2 = fixtures.technician(dealership, "Ben");
        otherDealershipTechnician = fixtures.technician(otherDealership, "Chloe");
        fixtures.skill(technician1, oilChange);
        fixtures.skill(technician2, oilChange);
        fixtures.skill(otherDealershipTechnician, oilChange);
        emma = fixtures.customer("Emma");
        emmasGolf = fixtures.vehicle(emma);
        emmasPolo = fixtures.vehicle(emma);
        duc = fixtures.customer("Duc");
        ducsFocus = fixtures.vehicle(duc);

        booking = NewAppointment.confirmed(dealership, emma, emmasGolf, oilChange, bay1, technician1,
                at("10:00"), at("11:00"));
    }

    @Nested
    @DisplayName("Appointment record")
    class AppointmentRecord {

        @Test
        @DisplayName("A valid appointment is stored as CONFIRMED with the same instants and a half-open range")
        void storesValidAppointment() {
            UUID id = fixtures.appointment(booking.at(at("10:00"), at("11:30")));

            StoredAppointment stored = jdbc.sql("""
                    SELECT status, start_at, end_at, version,
                           lower(period) = start_at AND upper(period) = end_at
                               AND lower_inc(period) AND NOT upper_inc(period) AS half_open
                    FROM appointment WHERE id = :id""")
                    .param("id", id)
                    .query((rs, row) -> new StoredAppointment(rs.getString("status"),
                            rs.getObject("start_at", OffsetDateTime.class), rs.getObject("end_at", OffsetDateTime.class),
                            rs.getLong("version"), rs.getBoolean("half_open")))
                    .single();

            assertThat(stored.status()).isEqualTo("CONFIRMED");
            assertThat(stored.startAt().toInstant()).isEqualTo(at("10:00").toInstant());
            assertThat(stored.endAt().toInstant()).isEqualTo(at("11:30").toInstant());
            assertThat(stored.halfOpen()).as("period is [start_at, end_at)").isTrue();
            assertThat(stored.version()).isZero();
        }

        @ParameterizedTest(name = "{0} to {1}")
        @DisplayName("An appointment whose end equals or precedes its start is rejected")
        @CsvSource({ "10:00, 10:00", "11:00, 10:00" })
        void rejectsEmptyOrInvertedRange(String start, String end) {
            assertRejected("23514", "appointment_time_order", () -> fixtures.appointment(booking.at(at(start), at(end))));
        }

        @Test
        @DisplayName("An appointment with status PENDING is rejected")
        void rejectsUnknownStatus() {
            assertRejected("23514", "appointment_status_valid", () -> fixtures.appointment(booking.withStatus("PENDING")));
        }

        @Test
        @DisplayName("An appointment for a customer that does not exist is rejected")
        void rejectsDanglingReference() {
            assertRejected("23503", "appointment_customer_fk",
                    () -> fixtures.appointment(booking.withVehicle(999_999, emmasGolf)));
        }
    }

    @Nested
    @DisplayName("No overlapping bookings of a service bay")
    class BayOverlap {

        @Test
        @DisplayName("A partially overlapping booking of the same bay is rejected")
        void rejectsPartialOverlap() {
            fixtures.appointment(booking);

            assertRejected("23P01", "appointment_no_bay_overlap", () -> fixtures.appointment(
                    booking.withTechnician(technician2).withVehicle(duc, ducsFocus).at(at("10:30"), at("11:30"))));
        }

        @Test
        @DisplayName("A booking may start exactly when the previous one ends, on the same bay, technician and vehicle")
        void allowsBackToBack() {
            fixtures.appointment(booking);

            fixtures.appointment(booking.at(at("11:00"), at("12:00")));

            assertThat(confirmedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("A cancelled appointment does not block its bay")
        void cancelledAppointmentDoesNotBlock() {
            UUID cancelled = fixtures.appointment(booking);
            jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();

            fixtures.appointment(booking);

            assertThat(confirmedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Overlap is detected when the bookings are given in different offsets (+07:00 and Z)")
        void detectsOverlapAcrossOffsets() {
            fixtures.appointment(booking);
            NewAppointment sameInstantsInUtc = booking.withTechnician(technician2).withVehicle(duc, ducsFocus)
                    .at(OffsetDateTime.parse("2026-11-02T03:30:00Z"), OffsetDateTime.parse("2026-11-02T04:30:00Z"));

            assertRejected("23P01", "appointment_no_bay_overlap", () -> fixtures.appointment(sameInstantsInUtc));
        }
    }

    @Nested
    @DisplayName("No overlapping bookings of a technician")
    class TechnicianOverlap {

        @Test
        @DisplayName("The same technician on another bay at an overlapping time is rejected")
        void rejectsSameTechnicianOnAnotherBay() {
            fixtures.appointment(booking);

            assertRejected("23P01", "appointment_no_technician_overlap", () -> fixtures.appointment(
                    booking.withBay(bay2).withVehicle(duc, ducsFocus).at(at("10:30"), at("11:30"))));
        }

        @Test
        @DisplayName("Different bays and technicians may be booked for the same period")
        void allowsDifferentTechniciansAtTheSameTime() {
            fixtures.appointment(booking);

            fixtures.appointment(booking.withBay(bay2).withTechnician(technician2).withVehicle(duc, ducsFocus));

            assertThat(confirmedCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("One booking per vehicle at a time")
    class VehicleOverlap {

        @Test
        @DisplayName("An overlapping booking of the same vehicle is rejected even with a different bay and technician")
        void rejectsDuplicateBookingOfVehicle() {
            fixtures.appointment(booking);

            assertRejected("23P01", "appointment_no_vehicle_overlap", () -> fixtures.appointment(
                    booking.withBay(bay2).withTechnician(technician2).at(at("10:30"), at("11:30"))));
        }

        @Test
        @DisplayName("Two vehicles of one customer may be booked for the same period")
        void allowsTwoVehiclesOfOneCustomer() {
            fixtures.appointment(booking);

            fixtures.appointment(booking.withBay(bay2).withTechnician(technician2).withVehicle(emma, emmasPolo));

            assertThat(confirmedCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Qualified technician from the same dealership")
    class QualificationAndDealership {

        @Test
        @DisplayName("A technician without a qualification for the service type is rejected")
        void rejectsUnqualifiedTechnician() {
            assertRejected("23503", "appointment_qualification_fk",
                    () -> fixtures.appointment(booking.withServiceType(brakeService)));
        }

        @Test
        @DisplayName("A service bay of another dealership is rejected")
        void rejectsBayFromAnotherDealership() {
            assertRejected("23503", "appointment_bay_fk",
                    () -> fixtures.appointment(booking.withBay(otherDealershipBay)));
        }

        @Test
        @DisplayName("A (qualified) technician of another dealership is rejected")
        void rejectsTechnicianFromAnotherDealership() {
            assertRejected("23503", "appointment_technician_fk",
                    () -> fixtures.appointment(booking.withTechnician(otherDealershipTechnician)));
        }

        @Test
        @DisplayName("A qualification referenced by an appointment cannot be deleted")
        void usedQualificationCannotBeDeleted() {
            fixtures.appointment(booking);

            assertRejected("23503", "appointment_qualification_fk", () -> jdbc.sql("""
                    DELETE FROM technician_skill WHERE technician_id = :technician AND service_type_id = :serviceType""")
                    .param("technician", technician1)
                    .param("serviceType", oilChange)
                    .update());
        }
    }

    @Nested
    @DisplayName("Idempotency key storage")
    class IdempotencyKey {

        @ParameterizedTest(name = "\"{0}\"")
        @DisplayName("Keys of up to 100 characters from A-Z a-z 0-9 . _ : - are accepted")
        @ValueSource(strings = { "6f1c2a8e-3b7d-4c11-9e5a-0d2f4b6c8a10", "client:order-42_v1.0", "k" })
        void acceptsWellFormedKey(String key) {
            fixtures.appointment(booking, key);

            assertThat(jdbc.sql("SELECT idempotency_key FROM appointment").query(String.class).single()).isEqualTo(key);
        }

        @Test
        @DisplayName("A key of exactly 100 characters is accepted")
        void acceptsLongestKey() {
            fixtures.appointment(booking, "k".repeat(100));

            assertThat(confirmedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("A second appointment with an existing key is rejected")
        void rejectsDuplicateKey() {
            fixtures.appointment(booking, "booking-1");
            NewAppointment unrelated = booking.withBay(bay2).withTechnician(technician2).withVehicle(duc, ducsFocus)
                    .at(at("14:00"), at("15:00"));

            assertRejected("23505", "appointment_idempotency_key_unique", () -> fixtures.appointment(unrelated, "booking-1"));
        }

        @Test
        @DisplayName("An appointment without a key is rejected")
        void rejectsMissingKey() {
            assertThat(assertRejected("23502", () -> fixtures.appointment(booking, null)).getColumn())
                    .isEqualTo("idempotency_key");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @DisplayName("A malformed key is rejected")
        @ValueSource(strings = { "has space", "", "slash/key", "ключ" })
        void rejectsMalformedKey(String key) {
            assertRejected("23514", "appointment_idempotency_key_format", () -> fixtures.appointment(booking, key));
        }

        @Test
        @DisplayName("A key of 101 characters is rejected")
        void rejectsTooLongKey() {
            assertRejected("23514", "appointment_idempotency_key_format",
                    () -> fixtures.appointment(booking, "k".repeat(101)));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @DisplayName("A request fingerprint that is not 64 lowercase hex characters is rejected")
        @ValueSource(strings = { "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde", "" })
        void rejectsMalformedFingerprint(String fingerprint) {
            assertRejected("23514", "appointment_request_fingerprint_format",
                    () -> fixtures.appointment(booking.withRequestFingerprint(fingerprint)));
        }
    }

    private long confirmedCount() {
        return jdbc.sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'").query(Long.class).single();
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-02T" + localTime + ":00+07:00");
    }

    private record StoredAppointment(String status, OffsetDateTime startAt, OffsetDateTime endAt, long version,
            boolean halfOpen) {
    }
}
