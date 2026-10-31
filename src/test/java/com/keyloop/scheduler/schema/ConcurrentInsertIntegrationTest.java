package com.keyloop.scheduler.schema;

import static com.keyloop.scheduler.SqlAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.keyloop.scheduler.DatabaseFixtures;
import com.keyloop.scheduler.DatabaseFixtures.NewAppointment;
import com.keyloop.scheduler.DatabaseIntegrationTest;

/**
 * Two transactions on two connections insert at the same time. The second insert must wait for the
 * first transaction's outcome and is decided by it, at the default READ COMMITTED isolation level.
 */
class ConcurrentInsertIntegrationTest extends DatabaseIntegrationTest {

    private final ExecutorService secondThread = Executors.newSingleThreadExecutor();

    private NewAppointment first;
    private NewAppointment overlappingOnSameBay;
    private NewAppointment unrelated;

    @BeforeEach
    void createWorkshop() {
        long dealership = fixtures.dealership("North");
        long oilChange = fixtures.serviceType("OIL_CHANGE", 60);
        long bay1 = fixtures.bay(dealership, "Bay 1");
        long bay2 = fixtures.bay(dealership, "Bay 2");
        long technician1 = fixtures.technician(dealership, "Alice");
        long technician2 = fixtures.technician(dealership, "Ben");
        fixtures.skill(technician1, oilChange);
        fixtures.skill(technician2, oilChange);
        long emma = fixtures.customer("Emma");
        long duc = fixtures.customer("Duc");
        long golf = fixtures.vehicle(emma);
        long focus = fixtures.vehicle(duc);

        first = NewAppointment.confirmed(dealership, emma, golf, oilChange, bay1, technician1, at("10:00"), at("11:00"));
        // Shares only the bay with "first", so the bay constraint is the one that decides.
        overlappingOnSameBay = first.withTechnician(technician2).withVehicle(duc, focus).at(at("10:30"), at("11:30"));
        // Shares nothing with "first" except, in the key test, the idempotency key.
        unrelated = first.withBay(bay2).withTechnician(technician2).withVehicle(duc, focus).at(at("14:00"), at("15:00"));
    }

    @AfterEach
    void stopSecondThread() {
        secondThread.shutdownNow();
    }

    @Test
    @DisplayName("Overlapping same-bay inserts: once the first commits, the waiting second fails with 23P01")
    void secondOverlappingInsertFailsWhenFirstCommits() throws Exception {
        try (Connection connection1 = transaction(); Connection connection2 = transaction()) {
            insert(connection1, first, "key-1");

            Future<UUID> second = insertInSecondThread(connection2, overlappingOnSameBay, "key-2");
            awaitBlockedOnLock(connection2, second);
            connection1.commit();

            assertRejected("23P01", "appointment_no_bay_overlap", () -> second.get(10, TimeUnit.SECONDS));
            connection2.rollback();
        }

        assertThat(committedKeys()).containsExactly("key-1");
    }

    @Test
    @DisplayName("Overlapping same-bay inserts: if the first rolls back, the waiting second succeeds")
    void secondOverlappingInsertSucceedsWhenFirstRollsBack() throws Exception {
        try (Connection connection1 = transaction(); Connection connection2 = transaction()) {
            insert(connection1, first, "key-1");

            Future<UUID> second = insertInSecondThread(connection2, overlappingOnSameBay, "key-2");
            awaitBlockedOnLock(connection2, second);
            connection1.rollback();

            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();
            connection2.commit();
        }

        assertThat(committedKeys()).containsExactly("key-2");
    }

    @Test
    @DisplayName("Inserts with the same idempotency key: exactly one commits, the other fails with 23505")
    void sameIdempotencyKeyCommitsOnce() throws Exception {
        try (Connection connection1 = transaction(); Connection connection2 = transaction()) {
            insert(connection1, first, "same-key");

            Future<UUID> second = insertInSecondThread(connection2, unrelated, "same-key");
            awaitBlockedOnLock(connection2, second);
            connection1.commit();

            assertRejected("23505", "appointment_idempotency_key_unique", () -> second.get(10, TimeUnit.SECONDS));
            connection2.rollback();
        }

        assertThat(committedKeys()).containsExactly("same-key");
    }

    private Connection transaction() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static UUID insert(Connection connection, NewAppointment appointment, String idempotencyKey) {
        JdbcClient client = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        return DatabaseFixtures.insertAppointment(client, appointment, idempotencyKey);
    }

    private Future<UUID> insertInSecondThread(Connection connection, NewAppointment appointment, String idempotencyKey) {
        return secondThread.submit(() -> insert(connection, appointment, idempotencyKey));
    }

    /** Waits until the second connection's backend is waiting on a lock held by the first transaction. */
    private void awaitBlockedOnLock(Connection connection, Future<UUID> pendingInsert) throws SQLException {
        int backendPid = connection.unwrap(PGConnection.class).getBackendPID();
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc
                .sql("SELECT coalesce(wait_event_type, '') FROM pg_stat_activity WHERE pid = :pid")
                .param("pid", backendPid)
                .query(String.class)
                .single()
                .equals("Lock"));
        assertThat(pendingInsert).as("second insert waits for the first transaction").isNotDone();
    }

    private List<String> committedKeys() {
        return jdbc.sql("SELECT idempotency_key FROM appointment ORDER BY idempotency_key").query(String.class).list();
    }

    private static OffsetDateTime at(String localTime) {
        return OffsetDateTime.parse("2026-11-02T" + localTime + ":00+07:00");
    }
}
