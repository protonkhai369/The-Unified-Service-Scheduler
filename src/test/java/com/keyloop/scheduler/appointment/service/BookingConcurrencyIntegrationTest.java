package com.keyloop.scheduler.appointment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.keyloop.scheduler.DatabaseFixtures;
import com.keyloop.scheduler.DatabaseFixtures.Workshop;
import com.keyloop.scheduler.appointment.AppointmentApiTestSupport;

/**
 * appointment-booking spec, "No double booking under concurrent requests", the concurrent scenarios of
 * "Idempotent booking retries" and "One booking per vehicle at a time", and the two paths that only a
 * writer bypassing the per-dealership-day lock can trigger: a lost race and a lock timeout.
 */
@ExtendWith(OutputCaptureExtension.class)
class BookingConcurrencyIntegrationTest extends AppointmentApiTestSupport {

    private final ExecutorService pool = Executors.newFixedThreadPool(20);

    @Autowired
    private MeterRegistry meterRegistry;

    private long emma;

    @BeforeEach
    void createCustomer() {
        emma = fixtures.customer("Emma");
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Parallel requests exceeding capacity")
    void parallelRequestsExceedingCapacity() throws Exception {
        Workshop workshop = london(60, 2, 2);
        List<Supplier<ResponseSpec>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long vehicle = fixtures.vehicle(emma);
            requests.add(() -> book(workshop.request(emma, vehicle, "2026-11-03T10:00:00Z")));
        }

        List<Reply> replies = concurrently(requests);

        assertThat(replies).filteredOn(reply -> reply.status() == 201).hasSize(2);
        assertThat(replies).filteredOn(reply -> reply.status() != 201)
                .hasSize(18)
                .allSatisfy(reply -> assertThat(reply.code()).isEqualTo("NO_AVAILABILITY"));
        assertThat(appointmentCount()).isEqualTo(2);
        assertThat(overlappingConfirmedPairs()).isZero();
    }

    @Test
    @DisplayName("Parallel requests within capacity")
    void parallelRequestsWithinCapacity() throws Exception {
        Workshop workshop = london(60, 4, 4);
        List<Supplier<ResponseSpec>> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long vehicle = fixtures.vehicle(emma);
            requests.add(() -> book(workshop.request(emma, vehicle, "2026-11-03T10:00:00Z")));
        }

        List<Reply> replies = concurrently(requests);

        assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        assertThat(replies).extracting(reply -> idOf(reply.body(), "serviceBay")).doesNotHaveDuplicates();
        assertThat(replies).extracting(reply -> idOf(reply.body(), "technician")).doesNotHaveDuplicates();
        assertThat(overlappingConfirmedPairs()).isZero();
    }

    @Test
    @DisplayName("Concurrent requests in different offsets")
    void concurrentRequestsInDifferentOffsets() throws Exception {
        Workshop workshop = london(60, 1, 1);
        long golf = fixtures.vehicle(emma);
        long corolla = fixtures.vehicle(emma);

        List<Reply> replies = concurrently(List.of(
                () -> book(workshop.request(emma, golf, "2026-11-03T17:00:00+07:00")),
                () -> book(workshop.request(emma, corolla, "2026-11-03T10:00:00Z"))));

        assertThat(replies).extracting(Reply::status).containsExactlyInAnyOrder(201, 409);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .singleElement()
                .satisfies(reply -> assertThat(reply.code()).isEqualTo("NO_AVAILABILITY"));
    }

    @Test
    @DisplayName("Concurrent duplicates")
    void concurrentDuplicates() throws Exception {
        Workshop workshop = london(60, 1, 1);
        long golf = fixtures.vehicle(emma);
        Map<String, Object> request = workshop.request(emma, golf, "2026-11-03T10:00:00Z");

        List<Reply> replies = concurrently(List.of(() -> book(request, "duplicate-1"), () -> book(request, "duplicate-1")));

        assertThat(replies).allSatisfy(reply -> assertThat(reply.status()).isEqualTo(201));
        assertThat(replies.get(0).body().get("id")).isEqualTo(replies.get(1).body().get("id"));
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent bookings of one vehicle at two dealerships")
    void concurrentBookingsOfOneVehicleAtTwoDealerships() throws Exception {
        Workshop london = london(60, 1, 1);
        Workshop saigon = saigon(45, 1, 1);
        long golf = fixtures.vehicle(emma);

        List<Reply> replies = concurrently(List.of(
                () -> book(london.request(emma, golf, "2026-11-03T09:30:00Z")),
                () -> book(saigon.request(emma, golf, "2026-11-03T16:30:00+07:00"))));

        assertThat(replies).extracting(Reply::status).containsExactlyInAnyOrder(201, 409);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .singleElement()
                .satisfies(reply -> assertThat(reply.code()).isEqualTo("VEHICLE_ALREADY_BOOKED"));
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Lost race is retried on fresh data")
    void lostRaceIsRetriedOnFreshData(CapturedOutput output) throws Exception {
        Workshop workshop = london(60, 2, 2);
        long golf = fixtures.vehicle(emma);
        long duc = fixtures.customer("Duc");
        long focus = fixtures.vehicle(duc);
        double conflictsBefore = conflicts();

        try (Connection writer = transaction()) {
            // A writer that does not queue on the dealership-day lock takes bay 1 (with technician 2) but
            // has not committed yet: the booking cannot see it, allocates bay 1 too and blocks on it.
            DatabaseFixtures.insertAppointment(client(writer),
                    workshop.appointment(duc, focus, 1, 2, at("10:00"), at("11:00")), "bypassing-writer");

            Future<EntityExchangeResult<Map<String, Object>>> booking = pool.submit(() ->
                    book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")).returnResult(JSON_OBJECT));
            await().atMost(Duration.ofSeconds(10)).until(this::anAppointmentInsertIsWaitingOnALock);
            writer.commit();

            EntityExchangeResult<Map<String, Object>> result = booking.get(10, TimeUnit.SECONDS);
            assertThat(result.getStatus().value()).isEqualTo(201);
            assertThat(idOf(result.getResponseBody(), "serviceBay")).isEqualTo(workshop.bay(2));
            assertThat(idOf(result.getResponseBody(), "technician")).isEqualTo(workshop.technician(1));
        }
        assertThat(conflicts()).isEqualTo(conflictsBefore + 1);
        assertThat(output.getOut()).contains("Booking attempt 1 lost a race on appointment_no_bay_overlap");
        assertThat(overlappingConfirmedPairs()).isZero();
    }

    @Test
    @DisplayName("Waiting too long for its turn")
    void waitingTooLongForItsTurn() throws Exception {
        Workshop workshop = london(60, 1, 1);
        long golf = fixtures.vehicle(emma);

        try (Connection holder = transaction()) {
            client(holder).sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", BookingAttempt.lockKey(workshop.dealershipId(), LocalDate.parse("2026-11-03")))
                    .query((row, number) -> Boolean.TRUE)
                    .single();

            problem(book(workshop.request(emma, golf, "2026-11-03T10:00:00Z")), 409, "SLOT_CONTENTION");
            holder.rollback();
        }
        assertThat(appointmentCount()).isZero();
    }

    private List<Reply> concurrently(List<Supplier<ResponseSpec>> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Reply>> pending = new ArrayList<>();
        for (Supplier<ResponseSpec> request : requests) {
            pending.add(pool.submit(() -> {
                start.await();
                EntityExchangeResult<Map<String, Object>> result = request.get().returnResult(JSON_OBJECT);
                return new Reply(result.getStatus().value(), result.getResponseBody());
            }));
        }
        start.countDown();
        List<Reply> replies = new ArrayList<>();
        for (Future<Reply> reply : pending) {
            replies.add(reply.get(30, TimeUnit.SECONDS));
        }
        return replies;
    }

    /** Pairs of confirmed appointments that share a bay or a technician and overlap in time. */
    private long overlappingConfirmedPairs() {
        return jdbc.sql("""
                SELECT count(*) FROM appointment a JOIN appointment b ON a.id < b.id
                WHERE a.status = 'CONFIRMED' AND b.status = 'CONFIRMED' AND a.period && b.period
                  AND (a.service_bay_id = b.service_bay_id OR a.technician_id = b.technician_id)""")
                .query(Long.class)
                .single();
    }

    private boolean anAppointmentInsertIsWaitingOnALock() {
        return jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query ILIKE 'insert into%appointment%'""")
                .query(Long.class)
                .single() > 0;
    }

    private double conflicts() {
        Counter counter = meterRegistry.find("scheduler.booking.conflicts").counter();
        return counter != null ? counter.count() : 0;
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static JdbcClient client(Connection connection) {
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-03T" + localTime + ":00Z");
    }

    private record Reply(int status, Map<String, Object> body) {

        String code() {
            return String.valueOf(body.get("code"));
        }
    }
}
