package com.keyloop.scheduler.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/**
 * observability spec, "Booking metrics" (the lost-race counter is checked by the concurrency test), and
 * the booking log events. Counters are shared by the whole test context, so tests compare deltas.
 */
@DisplayName("Booking metrics")
@ExtendWith(OutputCaptureExtension.class)
class BookingMetricsIntegrationTest extends AppointmentApiTestSupport {

    private static final String[] OUTCOMES = { "confirmed", "replayed", "no_availability", "contention", "rejected",
            "error" };

    @Autowired
    private MeterRegistry meterRegistry;

    private Workshop workshop;
    private long emma;
    private long golf;

    @BeforeEach
    void createWorkshop() {
        workshop = london(60, 1, 1);
        emma = fixtures.customer("Emma");
        golf = fixtures.vehicle(emma);
    }

    @Test
    @DisplayName("Confirmed booking counted")
    void confirmedBookingCounted() {
        double before = bookings("confirmed");

        booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

        assertThat(bookings("confirmed")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Replay counted")
    void replayCounted() {
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");
        booked(request, "metrics-replay");
        double confirmed = bookings("confirmed");
        double replayed = bookings("replayed");

        booked(request, "metrics-replay");

        assertThat(bookings("replayed")).isEqualTo(replayed + 1);
        assertThat(bookings("confirmed")).isEqualTo(confirmed);
    }

    @Test
    @DisplayName("Business rule rejection counted")
    void businessRuleRejectionCounted() {
        double before = bookings("rejected");
        long duc = fixtures.customer("Duc");

        problem(book(workshop.request(duc, golf, "2026-11-03T10:00:00Z")), 422, "VEHICLE_NOT_OWNED_BY_CUSTOMER");

        assertThat(bookings("rejected")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("No availability counted")
    void noAvailabilityCounted() {
        booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));
        double before = bookings("no_availability");

        problem(book(workshop.request(emma, fixtures.vehicle(emma), "2026-11-03T10:00:00Z")), 409, "NO_AVAILABILITY");

        assertThat(bookings("no_availability")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Unexpected failure counted")
    void unexpectedFailureCounted() {
        // The database only checks the zone's length; an unknown IANA id fails inside the booking flow.
        Workshop broken = fixtures.workshop("Broken zone", "Mars/Olympus", LocalTime.of(8, 0), LocalTime.of(18, 0),
                60, 1, 1);
        double errors = bookings("error");
        double rejected = bookings("rejected");

        problem(book(broken.request(emma, golf, "2026-11-03T10:00:00Z")), 500, "INTERNAL_ERROR");

        assertThat(bookings("error")).isEqualTo(errors + 1);
        assertThat(bookings("rejected")).isEqualTo(rejected);
    }

    @Test
    @DisplayName("Malformed request not counted as a booking")
    void malformedRequestNotCountedAsABooking() {
        double before = allBookings();

        problem(http.post().uri(APPOINTMENTS).contentType(APPLICATION_JSON).body("{\"dealershipId\": -1}").exchange(),
                400, "VALIDATION_FAILED");

        assertThat(allBookings()).isEqualTo(before);
    }

    @Test
    @DisplayName("Latency histogram buckets")
    void latencyHistogramBuckets() {
        booked(workshop.request(emma, golf, "2026-11-03T10:00:00Z"));

        String scrape = http.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(scrape)
                .contains("http_server_requests_seconds_bucket")
                .contains("scheduler_bookings_total{outcome=\"confirmed\"")
                .contains("scheduler_bookings_total{outcome=\"contention\"")
                .contains("scheduler_bookings_total{outcome=\"error\"")
                .contains("scheduler_booking_conflicts_total");
    }

    @Test
    void confirmationIsLoggedWithTheRequestIdAndNoCustomerData(CapturedOutput output) {
        Map<String, Object> appointment = http.post().uri(APPOINTMENTS).contentType(APPLICATION_JSON)
                .header("X-Request-Id", "booking-log-1")
                .body(workshop.request(emma, golf, "2026-11-03T10:00:00Z"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JSON_OBJECT).returnResult().getResponseBody();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(line -> line.contains("Appointment " + appointment.get("id") + " confirmed")))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("[booking-log-1]").doesNotContain("Emma")));
    }

    private double bookings(String outcome) {
        Counter counter = meterRegistry.find("scheduler.bookings").tag("outcome", outcome).counter();
        return counter != null ? counter.count() : 0;
    }

    private double allBookings() {
        double total = 0;
        for (String outcome : OUTCOMES) {
            total += bookings(outcome);
        }
        return total;
    }
}
