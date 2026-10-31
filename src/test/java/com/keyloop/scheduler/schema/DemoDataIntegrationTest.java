package com.keyloop.scheduler.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/**
 * Spec: database-platform, requirement "Demo data kept apart from schema and tests". Drives Flyway
 * directly on scratch databases with the demo location the application uses.
 */
class DemoDataIntegrationTest extends DatabaseIntegrationTest {

    private static final String SCHEMA_MIGRATIONS = "classpath:db/migration";
    private static final String DEMO_DATA = "classpath:db/demo";
    /** Test-only location holding V2__probe.sql, a stand-in for the next real schema migration. */
    private static final String NEXT_MIGRATION = "classpath:db/probe";

    private static final Map<String, Long> DEMO_ROWS = demoRows();

    @Test
    @DisplayName("A fresh database gets the demo dealerships, bays, technicians, qualifications, customers and vehicles")
    void loadsDemoDataIntoEmptyDatabase() {
        DataSource database = scratchDatabase("demo_fresh");
        JdbcClient client = JdbcClient.create(database);

        MigrateResult result = flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA).migrate();

        assertThat(result.migrations).extracting(m -> m.description).containsExactly("scheduler schema", "demo data");
        assertThat(rowCounts(client)).isEqualTo(DEMO_ROWS);
        assertThat(client.sql("SELECT time_zone FROM scheduler.dealership ORDER BY time_zone").query(String.class).list())
                .containsExactly("Asia/Ho_Chi_Minh", "Europe/London");
        assertThat(client.sql("SELECT count(*) FROM scheduler.technician WHERE NOT active").query(Long.class).single())
                .as("inactive technicians").isEqualTo(1);
    }

    @Test
    @DisplayName("Restarting, or changing the demo script after its data exists, inserts no demo row twice")
    void doesNotInsertDemoDataTwice(@TempDir Path changedDemoLocation) throws IOException {
        DataSource database = scratchDatabase("demo_again");
        JdbcClient client = JdbcClient.create(database);
        flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA).migrate();

        MigrateResult restart = flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA).migrate();

        assertThat(restart.migrationsExecuted).as("migrations run on restart").isZero();
        assertThat(rowCounts(client)).isEqualTo(DEMO_ROWS);

        // Same script with one more line: a new checksum, so Flyway re-runs the repeatable migration.
        String changedScript = new ClassPathResource("db/demo/R__demo_data.sql").getContentAsString(StandardCharsets.UTF_8)
                + "\n-- changed after the demo data was applied\n";
        Files.writeString(changedDemoLocation.resolve("R__demo_data.sql"), changedScript);

        MigrateResult changed = flyway(database, SCHEMA_MIGRATIONS, "filesystem:" + changedDemoLocation).migrate();

        assertThat(changed.migrations).extracting(m -> m.description).containsExactly("demo data");
        assertThat(rowCounts(client)).isEqualTo(DEMO_ROWS);
    }

    @Test
    @DisplayName("A versioned migration added after the demo data still applies")
    void laterVersionedMigrationStillApplies() {
        DataSource database = scratchDatabase("demo_then_v2");
        MigrateResult first = flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA).migrate();
        assertThat(first.migrations).extracting(m -> m.description).contains("demo data");

        MigrateResult next = flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA, NEXT_MIGRATION).migrate();

        assertThat(next.success).isTrue();
        assertThat(next.migrations).extracting(m -> m.version).containsExactly("2");
        assertThatNoException().isThrownBy(() -> flyway(database, SCHEMA_MIGRATIONS, DEMO_DATA, NEXT_MIGRATION).validate());
    }

    private static Map<String, Long> rowCounts(JdbcClient client) {
        Map<String, Long> counts = new LinkedHashMap<>();
        DEMO_ROWS.keySet().forEach(table -> counts.put(table,
                client.sql("SELECT count(*) FROM " + SCHEMA + "." + table).query(Long.class).single()));
        return counts;
    }

    private static Map<String, Long> demoRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("dealership", 2L);
        rows.put("service_type", 4L);
        rows.put("service_bay", 5L);
        rows.put("technician", 5L);
        rows.put("technician_skill", 13L);
        rows.put("customer", 2L);
        rows.put("vehicle", 3L);
        return rows;
    }
}
