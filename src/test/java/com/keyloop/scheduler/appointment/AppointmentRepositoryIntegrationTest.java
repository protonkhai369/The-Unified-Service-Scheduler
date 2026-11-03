package com.keyloop.scheduler.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.DatabaseIntegrationTest;
import com.keyloop.scheduler.appointment.entity.Appointment;
import com.keyloop.scheduler.appointment.entity.AppointmentStatus;
import com.keyloop.scheduler.appointment.repository.AppointmentRepository;
import com.keyloop.scheduler.appointment.repository.AppointmentView;
import com.keyloop.scheduler.appointment.repository.BookedSlot;
import com.keyloop.scheduler.catalog.entity.Technician;
import com.keyloop.scheduler.catalog.repository.ServiceBayRepository;
import com.keyloop.scheduler.catalog.repository.TechnicianRepository;
import com.keyloop.scheduler.scheduling.BusinessDay;

class AppointmentRepositoryIntegrationTest extends DatabaseIntegrationTest {

    private static final ZoneId SAIGON = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private AppointmentRepository appointments;

    @Autowired
    private ServiceBayRepository serviceBays;

    @Autowired
    private TechnicianRepository technicians;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Workshop saigon;
    private Workshop london;
    private long customer;
    private long vehicle;

    @BeforeEach
    void createWorkshops() {
        saigon = fixtures.workshop("Saigon", "Asia/Ho_Chi_Minh", LocalTime.of(7, 30), LocalTime.of(17, 30), 60, 2, 2);
        london = fixtures.workshop("London", "Europe/London", LocalTime.of(8, 0), LocalTime.of(18, 0), 60, 1, 1);
        customer = fixtures.customer("Emma");
        vehicle = fixtures.vehicle(customer);
    }

    @Test
    void dayCommitmentsAreTheConfirmedAppointmentsStartingOnThatLocalDay() {
        long otherVehicle = fixtures.vehicle(customer);
        fixtures.appointment(saigon.appointment(customer, vehicle, 1, 1, saigonTime("2026-11-03T07:30"), saigonTime("2026-11-03T08:30")));
        fixtures.appointment(saigon.appointment(customer, otherVehicle, 2, 2, saigonTime("2026-11-03T16:30"), saigonTime("2026-11-03T17:30")));
        fixtures.appointment(saigon.appointment(customer, vehicle, 1, 1, saigonTime("2026-11-02T16:30"), saigonTime("2026-11-02T17:30")));
        fixtures.appointment(saigon.appointment(customer, vehicle, 1, 1, saigonTime("2026-11-04T07:30"), saigonTime("2026-11-04T08:30")));
        fixtures.appointment(saigon.appointment(customer, vehicle, 1, 1, saigonTime("2026-11-03T10:00"), saigonTime("2026-11-03T11:00"))
                .withStatus("CANCELLED"));
        fixtures.appointment(london.appointment(customer, otherVehicle, 1, 1, OffsetDateTime.parse("2026-11-03T08:00Z"),
                OffsetDateTime.parse("2026-11-03T09:00Z")));

        BusinessDay day = new BusinessDay(LocalDate.parse("2026-11-03"), SAIGON, LocalTime.of(7, 30), LocalTime.of(17, 30));
        List<BookedSlot> slots = appointments.findConfirmedStartingBetween(saigon.dealershipId(),
                day.wholeDay().start(), day.wholeDay().end());

        assertThat(slots).containsExactlyInAnyOrder(
                new BookedSlot(saigon.bay(1), saigon.technician(1), Instant.parse("2026-11-03T00:30:00Z"),
                        Instant.parse("2026-11-03T01:30:00Z")),
                new BookedSlot(saigon.bay(2), saigon.technician(2), Instant.parse("2026-11-03T09:30:00Z"),
                        Instant.parse("2026-11-03T10:30:00Z")));
    }

    @Test
    void vehicleOverlapIsFoundAtAnyDealershipAndIgnoresBackToBackAndCancelled() {
        fixtures.appointment(london.appointment(customer, vehicle, 1, 1, OffsetDateTime.parse("2026-11-03T09:30Z"),
                OffsetDateTime.parse("2026-11-03T10:30Z")));
        long secondVehicle = fixtures.vehicle(customer);
        fixtures.appointment(london.appointment(customer, secondVehicle, 1, 1, OffsetDateTime.parse("2026-11-03T12:00Z"),
                OffsetDateTime.parse("2026-11-03T13:00Z")).withStatus("CANCELLED"));

        assertThat(appointments.vehicleBooked(vehicle, saigonInstant("2026-11-03T16:30"), saigonInstant("2026-11-03T17:15")))
                .isTrue();
        assertThat(appointments.vehicleBooked(vehicle, Instant.parse("2026-11-03T10:30:00Z"), Instant.parse("2026-11-03T11:30:00Z")))
                .as("back-to-back").isFalse();
        assertThat(appointments.vehicleBooked(secondVehicle, Instant.parse("2026-11-03T12:00:00Z"), Instant.parse("2026-11-03T13:00:00Z")))
                .as("cancelled").isFalse();
    }

    @Test
    void savedAppointmentReadsBackAsTheSameInstantsWithItsView() {
        Appointment appointment = Appointment.confirmed(saigon.dealershipId(), customer, vehicle, saigon.serviceTypeId(),
                saigon.bay(2), saigon.technician(1), saigonInstant("2026-11-03T10:00"), saigonInstant("2026-11-03T11:00"),
                "key-1", "ab".repeat(32), Instant.parse("2026-11-02T00:00:00Z"));
        appointments.saveAndFlush(appointment);

        AppointmentView view = appointments.findView(appointment.getId()).orElseThrow();
        assertThat(view.appointment().getStartAt()).isEqualTo(Instant.parse("2026-11-03T03:00:00Z"));
        assertThat(view.appointment().getEndAt()).isEqualTo(Instant.parse("2026-11-03T04:00:00Z"));
        assertThat(view.appointment().getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(view.timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(view.serviceBayName()).isEqualTo("Bay 2");
        assertThat(view.technicianName()).isEqualTo("Technician 1");

        assertThat(appointments.findViewByIdempotencyKey("key-1"))
                .hasValueSatisfying(byKey -> assertThat(byKey.appointment().getRequestFingerprint()).isEqualTo("ab".repeat(32)));
        assertThat(appointments.findViewByIdempotencyKey("unknown")).isEmpty();
        assertThat(appointments.findView(UUID.randomUUID())).isEmpty();
    }

    @Test
    void conditionalCancelChangesOnlyFutureConfirmedAppointments() {
        Instant now = Instant.parse("2026-11-03T02:00:00Z");
        UUID future = fixtures.appointment(saigon.appointment(customer, vehicle, 1, 1,
                saigonTime("2026-11-03T10:00"), saigonTime("2026-11-03T11:00")));
        UUID started = fixtures.appointment(saigon.appointment(customer, fixtures.vehicle(customer), 2, 2,
                saigonTime("2026-11-03T09:00"), saigonTime("2026-11-03T10:00")));
        UUID cancelled = fixtures.appointment(saigon.appointment(customer, fixtures.vehicle(customer), 1, 2,
                saigonTime("2026-11-03T13:00"), saigonTime("2026-11-03T14:00")).withStatus("CANCELLED"));

        assertThat(cancelInTransaction(future, now)).isEqualTo(1);
        assertThat(cancelInTransaction(started, now)).as("starts exactly now").isZero();
        assertThat(cancelInTransaction(cancelled, now)).isZero();

        assertThat(jdbc.sql("SELECT status || ':' || version || ':' || (updated_at = :now) FROM appointment WHERE id = :id")
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("id", future)
                .query(String.class)
                .single()).isEqualTo("CANCELLED:1:true");
    }

    @Test
    void candidatesAreActiveBaysAndActiveQualifiedTechniciansInIdOrder() {
        long inactiveBay = fixtures.bay(saigon.dealershipId(), "Closed bay", false);
        long inactive = fixtures.technician(saigon.dealershipId(), "Inactive", false);
        fixtures.skill(inactive, saigon.serviceTypeId());
        long retired = fixtures.technician(saigon.dealershipId(), "Retired skill");
        fixtures.skill(retired, saigon.serviceTypeId(), false);
        fixtures.technician(saigon.dealershipId(), "Unqualified");

        assertThat(serviceBays.findByDealershipIdAndActiveTrueOrderById(saigon.dealershipId()))
                .extracting(bay -> bay.getId())
                .containsExactly(saigon.bay(1), saigon.bay(2))
                .doesNotContain(inactiveBay);
        assertThat(technicians.findActiveQualified(saigon.dealershipId(), saigon.serviceTypeId()))
                .extracting(Technician::getId)
                .containsExactly(saigon.technician(1), saigon.technician(2));
        assertThat(technicians.findActiveQualified(london.dealershipId(), saigon.serviceTypeId())).isEmpty();
    }

    private int cancelInTransaction(UUID id, Instant now) {
        return new TransactionTemplate(transactionManager).execute(status -> appointments.cancelIfNotStarted(id, now));
    }

    private static OffsetDateTime saigonTime(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + ":00+07:00");
    }

    private static Instant saigonInstant(String localDateTime) {
        return saigonTime(localDateTime).toInstant();
    }
}
