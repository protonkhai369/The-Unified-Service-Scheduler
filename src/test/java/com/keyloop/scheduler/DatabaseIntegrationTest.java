package com.keyloop.scheduler;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.keyloop.scheduler.security.TestSecurityConfiguration;
import com.keyloop.scheduler.security.TestTokens;
import com.keyloop.scheduler.web.ProbeConfiguration;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "spring.flyway.locations=classpath:db/migration", "scheduler.booking-lock-timeout=2s",
                "SUPABASE_URL=" + TestSecurityConfiguration.SUPABASE_URL })
@AutoConfigureRestTestClient
@AutoConfigureMetrics
@Import({ TestcontainersConfiguration.class, TestSecurityConfiguration.class, ProbeConfiguration.class })
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

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RestTestClient http;

    @Autowired
    protected TestTokens tokens;

    protected DatabaseFixtures fixtures;

    private final List<String> scratchDatabases = new ArrayList<>();

    @BeforeEach
    void resetSchedulerTables() {
        jdbc.sql("TRUNCATE TABLE " + SCHEDULER_TABLES + " RESTART IDENTITY CASCADE").update();
        fixtures = new DatabaseFixtures(jdbc);
        clock.reset();
    }

    @AfterEach
    protected void dropScratchDatabases() {
        scratchDatabases.forEach(name -> jdbc.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update());
        scratchDatabases.clear();
    }

    protected DataSource scratchDatabase(String name) {
        jdbc.sql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)").update();
        jdbc.sql("CREATE DATABASE " + name).update();
        scratchDatabases.add(name);
        String url = "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), name);
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }

    protected static Flyway flyway(DataSource database, String... locations) {
        return Flyway.configure()
                .dataSource(database)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations(locations)
                .load();
    }
}
