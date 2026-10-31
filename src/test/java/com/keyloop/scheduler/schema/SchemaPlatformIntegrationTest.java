package com.keyloop.scheduler.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.keyloop.scheduler.DatabaseIntegrationTest;

/** Spec: database-platform (isolated schema, portable migrations, tests start clean). */
class SchemaPlatformIntegrationTest extends DatabaseIntegrationTest {

    private static final List<String> SCHEDULER_TABLES = List.of("appointment", "customer", "dealership",
            "flyway_schema_history", "service_bay", "service_type", "technician", "technician_skill", "vehicle");

    private static final List<String> DATA_API_ROLES = List.of("anon", "authenticated");

    @AfterEach
    void dropSimulatedDataApiRoles() {
        // Roles are cluster-wide; drop the database holding their privileges first.
        dropScratchDatabases();
        DATA_API_ROLES.forEach(role -> jdbc.sql("DROP ROLE IF EXISTS " + role).update());
    }

    @Test
    @DisplayName("Every application table, including the migration history, is in schema scheduler")
    void tablesLiveOnlyInSchedulerSchema() {
        List<String> userTables = jdbc.sql("""
                SELECT n.nspname || '.' || c.relname
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
                ORDER BY 1""")
                .query(String.class)
                .list();

        assertThat(userTables).containsExactlyElementsOf(SCHEDULER_TABLES.stream().map(t -> SCHEMA + "." + t).toList());
    }

    @Test
    @DisplayName("The public schema holds no relation and no function")
    void publicSchemaStaysEmpty() {
        long relations = jdbc.sql("SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = 'public'::regnamespace")
                .query(Long.class).single();
        long functions = jdbc.sql("SELECT count(*) FROM pg_catalog.pg_proc WHERE pronamespace = 'public'::regnamespace")
                .query(Long.class).single();

        assertThat(relations).as("tables, views, sequences and indexes in public").isZero();
        assertThat(functions).as("functions in public").isZero();
    }

    @Test
    @DisplayName("btree_gist is installed in schema extensions")
    void btreeGistInstalledInExtensionsSchema() {
        List<String> schemas = jdbc.sql("SELECT extnamespace::regnamespace::text FROM pg_catalog.pg_extension WHERE extname = 'btree_gist'")
                .query(String.class)
                .list();

        assertThat(schemas).containsExactly("extensions");
    }

    @Test
    @DisplayName("Tests run on the schema migrations only: no demo data migration is applied")
    void testDatabaseHasNoDemoData() {
        List<String> applied = jdbc.sql("SELECT script FROM flyway_schema_history WHERE type <> 'SCHEMA' ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(applied).containsExactly("V1__scheduler_schema.sql");
    }

    @Test
    @DisplayName("Where Supabase's Data API roles exist, they lose every privilege on the scheduler schema")
    void revokesDataApiRolesWhenTheyExist() {
        DATA_API_ROLES.forEach(role -> jdbc.sql("CREATE ROLE " + role + " NOLOGIN").update());
        DataSource database = scratchDatabase("revoke_check");
        JdbcClient scratch = JdbcClient.create(database);
        // Simulate a project that exposed the schema before V1 ran.
        scratch.sql("CREATE SCHEMA " + SCHEMA).update();
        scratch.sql("GRANT USAGE ON SCHEMA " + SCHEMA + " TO anon, authenticated").update();
        scratch.sql("ALTER DEFAULT PRIVILEGES IN SCHEMA " + SCHEMA + " GRANT ALL ON TABLES TO anon, authenticated").update();
        scratch.sql("ALTER DEFAULT PRIVILEGES IN SCHEMA " + SCHEMA + " GRANT ALL ON SEQUENCES TO anon, authenticated").update();

        flyway(database, "classpath:db/migration").migrate();

        for (String role : DATA_API_ROLES) {
            assertThat(scratch.sql("SELECT has_schema_privilege(:role, :schema, 'USAGE')")
                    .param("role", role).param("schema", SCHEMA).query(Boolean.class).single())
                    .as("%s has USAGE on %s", role, SCHEMA).isFalse();
        }
        assertThat(scratch.sql("""
                SELECT count(*) FROM information_schema.role_table_grants
                WHERE table_schema = :schema AND grantee IN ('anon', 'authenticated')""")
                .param("schema", SCHEMA).query(Long.class).single())
                .as("table privileges held by anon/authenticated").isZero();
        assertThat(scratch.sql("""
                SELECT count(*) FROM pg_catalog.pg_default_acl d, aclexplode(d.defaclacl) a
                WHERE d.defaclnamespace = (SELECT oid FROM pg_catalog.pg_namespace WHERE nspname = :schema)
                  AND a.grantee IN (SELECT oid FROM pg_catalog.pg_roles WHERE rolname IN ('anon', 'authenticated'))""")
                .param("schema", SCHEMA).query(Long.class).single())
                .as("default privileges granted to anon/authenticated").isZero();
    }
}
