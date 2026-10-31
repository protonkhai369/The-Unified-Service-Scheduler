package com.keyloop.scheduler;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests that need the real schema. Every subclass shares one Spring context and one
 * PostgreSQL container; Flyway applies only the schema migrations (never the demo data), and every
 * scheduler table is emptied before each test.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration")
@Import(TestcontainersConfiguration.class)
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
