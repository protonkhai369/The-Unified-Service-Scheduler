package com.keyloop.scheduler;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.keyloop.scheduler.web.ProbeConfiguration;

/**
 * Base for tests that need the real schema or the real HTTP stack. Every subclass shares one Spring
 * context, one PostgreSQL container and one embedded server on a random port; Flyway applies only the
 * schema migrations (never the demo data), and every scheduler table is emptied before each test.
 * A real server rather than MockMvc, because only a servlet container forwards failures to /error.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.locations=classpath:db/migration")
@AutoConfigureRestTestClient
@Import({ TestcontainersConfiguration.class, ProbeConfiguration.class })
public abstract class DatabaseIntegrationTest {

    protected static final String SCHEMA = "scheduler";

    private static final String SCHEDULER_TABLES = String.join(", ", "appointment", "technician_skill",
            "vehicle", "customer", "technician", "service_bay", "service_type", "dealership");

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    /** Sends real HTTP requests to the embedded server. */
    @Autowired
    protected RestTestClient http;

    protected DatabaseFixtures fixtures;

    private final List<String> scratchDatabases = new ArrayList<>();

    @BeforeEach
    void resetSchedulerTables() {
        jdbc.sql("TRUNCATE TABLE " + SCHEDULER_TABLES + " RESTART IDENTITY CASCADE").update();
        fixtures = new DatabaseFixtures(jdbc);
    }

    @AfterEach
    protected void dropScratchDatabases() {
        scratchDatabases.forEach(name -> jdbc.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update());
        scratchDatabases.clear();
    }

    /**
     * Creates an empty database in the shared container, for tests that drive Flyway themselves
     * and must not touch the schema the Spring context migrated. Dropped after the test.
     */
    protected DataSource scratchDatabase(String name) {
        jdbc.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        jdbc.sql("CREATE DATABASE " + name).update();
        scratchDatabases.add(name);
        String url = "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), name);
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    /** Flyway configured like the application (schema {@value #SCHEMA}) for the given locations. */
    protected static Flyway flyway(DataSource database, String... locations) {
        return Flyway.configure()
                .dataSource(database)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations(locations)
                .load();
    }
}
