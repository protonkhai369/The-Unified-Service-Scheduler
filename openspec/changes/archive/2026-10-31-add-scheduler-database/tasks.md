 ## 1. Spike: prove the risky SQL on both targets

- [x] 1.1 Write a throwaway SQL script (not committed) that creates schema `extensions`, installs `btree_gist` there, and in a scratch schema creates a table with `start_at`/`end_at`, a generated `period tstzrange '[)'` column and a partial `EXCLUDE USING gist (... WITH =, period WITH &&)` constraint; run it on a Testcontainers `postgres:17-alpine` and confirm an overlapping insert fails with `23P01` naming the constraint and a back-to-back insert succeeds
- [x] 1.2 Run the same script on Supabase inside a transaction that is rolled back (nothing left behind) with `search_path` set to the scratch schema, confirming the GiST operator classes resolve from `extensions`; record both results in `docs/AI_LOG.md`

## 2. Test infrastructure

- [x] 2.1 Add an abstract `DatabaseIntegrationTest` base: `@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, property `spring.flyway.locations=classpath:db/migration`, and a `@BeforeEach` that truncates all scheduler tables with `RESTART IDENTITY CASCADE`
- [x] 2.2 Add a fixtures helper using `JdbcClient` that inserts dealerships, service types, bays, technicians, skills, customers and vehicles and returns their ids
- [x] 2.3 Add an assertion helper that runs an insert and asserts the SQLSTATE and constraint name from the underlying `PSQLException`

## 3. Schema migration (tests first)

- [x] 3.1 Write `SchemaPlatformIntegrationTest` (expected to fail first): application tables and `flyway_schema_history` exist only in `scheduler`, nothing in `public`, `btree_gist` is installed in `extensions`, and the test database holds no demo rows
- [x] 3.2 Write `ReferenceDataIntegrationTest`: malformed VIN, duplicate VIN, non-positive duration and closing-before-opening are rejected with the SQLSTATEs in the spec
- [x] 3.3 Write `AppointmentConstraintsIntegrationTest`: valid appointment stored; inverted range, unknown status and dangling reference rejected; bay, technician and vehicle overlap rejected with the right constraint names; back-to-back allowed; cancelled does not block; overlap detected across `+07:00`/`Z` offsets; different technicians and a customer's two vehicles at the same time allowed; unqualified technician, foreign bay and foreign technician rejected; used qualification cannot be deleted; duplicate, missing and malformed idempotency keys rejected
- [x] 3.4 Write `ConcurrentInsertIntegrationTest` with two connections on separate threads: overlapping same-bay inserts (first commits → second fails with `23P01`; first rolls back → second succeeds) and same-idempotency-key inserts (exactly one commits)
- [x] 3.5 Write `src/main/resources/db/migration/V1__scheduler_schema.sql` as specified in design.md (extensions schema, tables, checks, composite foreign keys, three named exclusion constraints, unique idempotency key, indexes, guarded revokes) until tasks 3.1-3.4 pass
- [x] 3.6 Confirm `ServiceSchedulerApplicationTests.contextLoads` still passes with `ddl-auto: validate` against the new schema

## 4. Demo data

- [x] 4.1 Add `src/main/resources/db/demo/R__demo_data.sql`, inserting only when `dealership` is empty: two dealerships (`Europe/London`, `Asia/Ho_Chi_Minh`), four service types, bays, technicians (one inactive), skills, two customers and three vehicles with valid VINs
- [x] 4.2 Set `spring.flyway.locations` in `application.yml` to `classpath:db/migration,classpath:db/demo`
- [x] 4.3 Write `DemoDataIntegrationTest` that drives Flyway directly on the container: demo rows exist after migrating with the demo location; migrating again inserts nothing new; adding a test-only location with a later `V2__probe.sql` still applies cleanly after the demo data

## 5. Apply to Supabase

- [x] 5.1 Start the application against Supabase (`./mvnw spring-boot:run`); confirm Flyway applies `V1` and `R__demo_data` and `/actuator/health/readiness` reports `UP`
- [x] 5.2 Verify with read-only SQL: no tables in `public`, all scheduler tables in `scheduler`, `btree_gist` in `extensions`, `anon` and `authenticated` without `USAGE` on `scheduler`
- [x] 5.3 Ask the project owner to re-run Supabase's Security Advisor and confirm there is no "RLS Disabled in Public" finding
- [x] 5.4 Restart the application and confirm no migration runs again

## 6. Documentation

- [x] 6.1 Create `docs/SYSTEM_DESIGN.md` with the data model section (diagram, "who enforces what" table, concurrency explanation) and the assumptions from the proposal
- [x] 6.2 Create `docs/AI_LOG.md` covering this change: the unparsable `openspec/config.yaml` found through an OpenSpec warning, the never-executed previous schema, the `V100` demo-data ordering bug, the missing per-vehicle duplicate guard, the twin-request race, and the spike results
- [x] 6.3 Update `AGENTS.md` and `openspec/config.yaml` with the conventions this change establishes: tables only in `scheduler`, extensions in `extensions`, demo data as a guarded repeatable migration, integration tests named `*IntegrationTest`

## 7. Verification

- [x] 7.1 Run `./mvnw test` and confirm every test passes, quoting the Surefire summary
- [x] 7.2 Map every scenario in `specs/scheduling-data-model` and `specs/database-platform` to a passing test or a check from section 5, and list any gap
- [x] 7.3 Run `openspec validate add-scheduler-database --strict` and confirm it passes
