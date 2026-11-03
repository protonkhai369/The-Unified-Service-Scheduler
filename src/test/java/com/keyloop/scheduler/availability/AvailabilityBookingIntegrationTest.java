package com.keyloop.scheduler.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

class AvailabilityBookingIntegrationTest extends AppointmentApiTestSupport {

    private long emma;

    @BeforeEach
    void createCustomer() {
        emma = fixtures.customer("Emma");
    }

    @Test
    void listsExactlyTheStartsThatBookingAccepts() {
        Workshop workshop = london(60, 3, 2);
        fixtures.appointment(workshop.appointment(emma, fixtures.vehicle(emma), 1, 1, at("09:00"), at("10:00")));
        fixtures.appointment(workshop.appointment(emma, fixtures.vehicle(emma), 2, 2, at("09:30"), at("10:30")));
        fixtures.appointment(workshop.appointment(emma, fixtures.vehicle(emma), 3, 1, at("11:15"), at("12:45")));
        fixtures.appointment(workshop.appointment(emma, fixtures.vehicle(emma), 1, 2, at("12:00"), at("13:00")));
        long golf = fixtures.vehicle(emma);

        List<Map<String, Object>> slots = slots(workshop, "2026-11-03");
        List<String> listed = startTimes(slots);
        List<String> unlisted = new ArrayList<>();
        for (LocalTime start = LocalTime.of(8, 0); !start.isAfter(LocalTime.of(17, 0)); start = start.plusMinutes(30)) {
            if (!listed.contains("2026-11-03T" + start + ":00Z")) {
                unlisted.add("2026-11-03T" + start + ":00Z");
            }
        }
        assertThat(unlisted).containsExactly("2026-11-03T09:00:00Z", "2026-11-03T09:30:00Z", "2026-11-03T11:30:00Z",
                "2026-11-03T12:00:00Z", "2026-11-03T12:30:00Z");

        for (String start : listed) {
            Map<String, Object> appointment = booked(workshop.request(emma, golf, start));
            cancel(appointment.get("id")).expectStatus().isOk();
        }
        for (String start : unlisted) {
            problem(book(workshop.request(emma, golf, start)), 409, "NO_AVAILABILITY");
        }

        Map<String, Object> first = slots.getFirst();
        int capacity = Math.min((Integer) first.get("availableBays"), (Integer) first.get("availableTechnicians"));
        assertThat(capacity).isEqualTo(2);
        for (int i = 0; i < capacity; i++) {
            booked(workshop.request(emma, fixtures.vehicle(emma), (String) first.get("startTime")));
        }
        problem(book(workshop.request(emma, fixtures.vehicle(emma), (String) first.get("startTime"))), 409,
                "NO_AVAILABILITY");
    }

    @Test
    @DisplayName("A booking removes the slots it overlaps")
    void aBookingRemovesTheSlotsItOverlaps() {
        Workshop workshop = london(60, 1, 1);

        booked(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z"));

        assertThat(startTimes(slots(workshop, "2026-11-03")))
                .contains("2026-11-03T09:00:00Z", "2026-11-03T11:00:00Z")
                .doesNotContain("2026-11-03T09:30:00Z", "2026-11-03T10:00:00Z", "2026-11-03T10:30:00Z");
    }

    @Test
    @DisplayName("Listed slot taken by another customer")
    void listedSlotTakenByAnotherCustomer() {
        Workshop workshop = london(60, 1, 1);
        long duc = fixtures.customer("Duc");
        assertThat(slots(workshop, "2026-11-03")).contains(Map.of("startTime", "2026-11-03T10:00:00Z",
                "endTime", "2026-11-03T11:00:00Z", "availableBays", 1, "availableTechnicians", 1));

        booked(workshop.request(duc, fixtures.vehicle(duc), "2026-11-03T10:00:00Z"));

        problem(book(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-03T" + localTime + ":00Z");
    }
}
