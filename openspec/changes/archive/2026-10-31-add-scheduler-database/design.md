## Context

- The application was reset to a skeleton on 2026-10-30. It connects to a Supabase project
  (PostgreSQL 17.11) through the session pooler; Flyway already created an empty
  `scheduler.flyway_schema_history`. `btree_gist` is available on Supabase but not installed.
- Tests run on a Testcontainers `postgres:17-alpine` wired with `@ServiceConnection`
  (`src/test/java/com/keyloop/scheduler/TestcontainersConfiguration.java`), so they never touch
  Supabase.
- `application.yml` already points Flyway (`default-schema`, `schemas`), Hikari (`schema`) and
  Hibernate (`default_schema`) at `scheduler`, and Hibernate runs with `ddl-auto: validate`.
- The previous schema was never executed against a database (only pure unit tests ran), so none of
  its constraints were proven. On 2026-10-30 Supabase's Security Advisor flagged a table in `public`
  as readable and writable by the anonymous role through the Data API.
- Two audiences: Keyloop reviewers, who run the project locally without Supabase credentials, and the
  candidate, who demos it on Supabase.

## Goals / Non-Goals

**Goals:**

- One versioned migration that creates the whole Scenario A data model, identical on Supabase and on
  plain PostgreSQL 17.
- Every booking invariant that can be expressed in SQL is enforced by PostgreSQL, so correctness does
  not depend on request timing or on application code being bug-free.
- Nothing reachable through Supabase's Data API.
- Each invariant proven by an integration test, including concurrent inserts.

**Non-Goals:** API endpoints, JPA entities, allocation and retry logic, the HTTP idempotency flow,
availability search, technician shifts, bay types, holidays, RLS policies (see proposal).

## Data model

```
dealership 1─* service_bay              service_type
     │                                  ▲     ▲
     1─* technician ─*  technician_skill ─┘     │
                          ▲ (technician_id, service_type_id)
customer 1─* vehicle      │                     │
     │          │         │                     │
     1          1         │                     │
     *          *         │                     *
     └───────── appointment ──────────────────────┘
                 ├─ (dealership_id, service_bay_id) ─▶ service_bay(dealership_id, id)
                 ├─ (dealership_id, technician_id)  ─▶ technician(dealership_id, id)
                 └─ (technician_id, service_type_id) ─▶ technician_skill
```

| Table | Columns (all `NOT NULL` unless noted) | Constraints |
| --- | --- | --- |
| `dealership` | `id bigint identity`, `name text`, `time_zone text`, `opens time`, `closes time` | `closes > opens` |
| `service_type` | `id`, `code text`, `name text`, `duration_minutes int` | `code` unique, `duration_minutes > 0` |
| `service_bay` | `id`, `dealership_id`, `name text`, `active bool` | unique `(dealership_id, name)`, unique `(dealership_id, id)` |
| `technician` | `id`, `dealership_id`, `name text`, `active bool` | unique `(dealership_id, id)` |
| `technician_skill` | `technician_id`, `service_type_id`, `active bool` | PK `(technician_id, service_type_id)` |
| `customer` | `id`, `name text`, `email text`, `phone text` (nullable) | `email` unique |
| `vehicle` | `id`, `customer_id`, `vin text`, `make text`, `model text`, `model_year int` (nullable) | `vin` unique, `vin ~ '^[A-HJ-NPR-Z0-9]{17}$'` |
| `appointment` | `id uuid default gen_random_uuid()`, `dealership_id`, `customer_id`, `vehicle_id`, `service_type_id`, `service_bay_id`, `technician_id`, `start_at timestamptz`, `end_at timestamptz`, `period tstzrange` (generated), `status text`, `idempotency_key text`, `request_fingerprint text`, `created_at`, `updated_at` timestamptz, `version bigint` | see Decisions 2-6 |

Text columns get length `CHECK`s where a limit matters (names ≤ 200, codes ≤ 50, email ≤ 320).

## Decisions

### 1. A dedicated `scheduler` schema instead of `public` plus RLS

Supabase exposes `public` through PostgREST and grants the anonymous role privileges on new tables
there. The backend talks to PostgreSQL only over JDBC, so the simplest guarantee is to keep every
object out of `public`: Flyway's `default-schema`/`schemas` create `scheduler`, which is not in the
Data API's exposed schemas and on which `anon`/`authenticated` hold no `USAGE`. The migration also
revokes explicitly (see Decision 9) so the guarantee survives someone exposing the schema later.

Alternatives: RLS on every table (must be remembered for every future table, needs policies that
would all be "deny"); disabling the Data API in the dashboard (good extra hardening, but a manual,
unversioned setting, so it is recommended rather than relied on).

### 2. Booking invariants live in PostgreSQL

Overlap checks in application code alone are check-then-act races: two requests can both see a free
bay and both insert. `EXCLUDE` and `UNIQUE` constraints are checked against an index at insert time,
including rows of concurrent, uncommitted transactions:

```
 T1: INSERT bay 1, 10:00-11:00 ─────────────── COMMIT
 T2:        INSERT bay 1, 10:30-11:30 ── waits for T1 ──▶ T1 committed → 23P01, T2 rolls back
                                                         (T1 rolled back → T2 succeeds)
```

This holds at the default `READ COMMITTED` isolation level; no `SERIALIZABLE`, advisory locks or
`SELECT ... FOR UPDATE` are needed. The application will still check availability first (cheap,
gives good error messages) and treat the constraint as the final arbiter.

### 3. Time as two instants plus a generated range

`start_at`/`end_at timestamptz` (simple to map from `Instant` later) and
`period tstzrange GENERATED ALWAYS AS (CASE WHEN start_at < end_at THEN tstzrange(start_at, end_at, '[)') END) STORED`
used by the constraints and range queries. Half-open `[)` lets an appointment start when another
ends. Values are instants, so offsets in input never cause missed overlaps. `end_at` is stored rather
than derived from the service type, so changing a duration later does not rewrite history.

The `CASE` guard was added during implementation: generated columns are computed before `CHECK`
constraints, and `tstzrange()` raises a generic `22000` for an inverted range, so without it the
spec's `23514` on `appointment_time_order` is unreachable. Every stored row passes that check, so
`period` is never `NULL` in practice.

Alternatives: repeating the `tstzrange(...)` expression in each constraint (noisier, same
semantics); a single range column (clean SQL, but awkward to map in Hibernate).

### 4. Three partial exclusion constraints with stable names

```
appointment_no_bay_overlap         EXCLUDE USING gist (service_bay_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED')
appointment_no_technician_overlap  EXCLUDE USING gist (technician_id  WITH =, period WITH &&) WHERE (status = 'CONFIRMED')
appointment_no_vehicle_overlap     EXCLUDE USING gist (vehicle_id     WITH =, period WITH &&) WHERE (status = 'CONFIRMED')
```

The partial predicate means cancelling frees resources without deleting history. Constraint names
are a contract: the booking change will retry on bay/technician conflicts but report
`VEHICLE_ALREADY_BOOKED` on the vehicle one. Their GiST indexes also serve lookups by bay,
technician and vehicle.

### 5. Composite foreign keys for qualification and dealership consistency

`(technician_id, service_type_id) → technician_skill`, `(dealership_id, service_bay_id) →
service_bay(dealership_id, id)` and `(dealership_id, technician_id) → technician(dealership_id, id)`.
A bug that assigns an unqualified technician or another dealership's bay is rejected by the database.
Cost: a qualification referenced by an appointment cannot be deleted, so `technician_skill.active`
exists to retire a skill instead.

Vehicle ownership is deliberately not a foreign key: vehicles change owners, and an FK on
`(customer_id, vehicle_id)` would block transferring a vehicle that has appointment history. The
booking change checks ownership at booking time.

### 6. Idempotency key on the appointment row

`idempotency_key text NOT NULL` with `CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,100}$')` and
constraint `appointment_idempotency_key_unique`; `request_fingerprint text NOT NULL` with
`CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')`. The key is written by the same `INSERT` as the
appointment, so key and booking commit atomically and the unique index arbitrates concurrent
duplicates; there is no in-progress state, lock table or expiry job.

Alternative: a separate `idempotency_record` table (per-client scope, stored responses, TTL). Deferred
until several endpoints need it or clients authenticate.

### 7. Identifiers, types and indexes

- `bigint GENERATED ALWAYS AS IDENTITY` for reference data; `uuid` for appointments because their ids
  are handed to customers and must not be guessable. `gen_random_uuid()` is the default; the
  application may supply its own.
- `text` with `CHECK` instead of `varchar(n)`; `status text CHECK (status IN ('CONFIRMED','CANCELLED'))`
  instead of an enum type, so new states are a one-line change.
- PostgreSQL does not index foreign keys automatically. Explicit indexes on `appointment(customer_id)`,
  `appointment(service_type_id)`, `service_bay(dealership_id)` is covered by its unique pair,
  `technician(dealership_id)` likewise, `vehicle(customer_id)`; bay/technician/vehicle lookups use the
  GiST indexes. One partial index `appointment(dealership_id, start_at) WHERE status = 'CONFIRMED'`
  serves "a dealership's bookings for a day".

### 8. Optimistic locking column

`version bigint NOT NULL DEFAULT 0` on `appointment`, for the later JPA `@Version` mapping that
protects concurrent cancel/update.

### 9. Portable across Supabase and plain PostgreSQL

```sql
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA extensions;
```

Supabase keeps extensions in `extensions`; plain PostgreSQL does not have that schema, hence
`IF NOT EXISTS`. Index operator classes are resolved by type and access method, not through
`search_path`, so the GiST operator classes are found while Flyway's `search_path` is `scheduler`
(to be confirmed by the spike, task 1). Revokes run in a `DO` block only when the role exists:

```sql
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    REVOKE ALL ON SCHEMA scheduler FROM anon, authenticated;
    ALTER DEFAULT PRIVILEGES IN SCHEMA scheduler REVOKE ALL ON TABLES FROM anon, authenticated;
  END IF;
END $$;
```

### 10. Demo data as a guarded repeatable migration

`db/demo/R__demo_data.sql`, a Flyway repeatable migration. Repeatable migrations run after all
versioned ones and again only when their checksum changes, and the script inserts only when
`dealership` is empty. A versioned `V100__demo_data.sql` (the previous design) would break the first
later `V2__...` on any database where V100 had been applied: Flyway treats a lower, unapplied version
as ignored and fails validation. `application.yml` lists `classpath:db/migration,classpath:db/demo`;
tests override `spring.flyway.locations` to `classpath:db/migration` only.

### 11. Test approach

- One abstract base for database tests: `@SpringBootTest`, `@Import(TestcontainersConfiguration)`,
  property `spring.flyway.locations=classpath:db/migration`. All tests share one cached context and one
  container.
- Tests use `JdbcClient` (direct SQL, no entities yet) and assert SQLSTATE and constraint name from
  the `SQLException` cause.
- Isolation: `TRUNCATE ... RESTART IDENTITY CASCADE` on the scheduler tables before each test; a
  small fixture helper inserts dealerships, bays, technicians and skills per test.
- Concurrency: two connections from the pool, each in its own transaction; T1 inserts without
  committing, T2's insert is started on another thread and must block, T1 commits, T2 must fail with
  the expected SQLSTATE (and must succeed in a variant where T1 rolls back).
- Integration tests are named `*IntegrationTest` and run by Surefire, so `./mvnw test` stays the one
  command reviewers run. This deliberately deviates from the `java-testcontainers` skill's
  `*IT`/Failsafe advice; the suite is small and Docker is already required.

## Observability

No new metrics or log events. Flyway logs each applied migration at startup; the readiness probe
(`/actuator/health/readiness`) already includes the database. Supabase's Security Advisor is the
external check for Decision 1. Booking metrics arrive with the booking change.

## Risks / Trade-offs

- [Generated `tstzrange` column and GiST operator classes from `extensions` are unproven on both
  targets] → task 1 is a spike against Testcontainers and Supabase before the full migration.
- [Supabase pooler hiccups: a transient `EAUTHQUERY` was observed on 2026-10-31] → pool size 5;
  retry the start; tests never depend on Supabase.
- [Demo data lands in the shared Supabase database] → inserted only into empty tables; resettable by
  dropping the `scheduler` schema in development.
- [Composite FKs make retiring a skill a soft change] → `technician_skill.active`.
- [Global idempotency keys could collide between future clients] → UUID keys make collisions
  negligible; per-client scoping is listed as future work.
- [Schema mistakes become permanent once V1 reaches Supabase] → spike first; after V1 is applied
  there, fixes go into `V2+`, never edits to `V1`.

## Migration Plan

1. Run the integration tests on Testcontainers (`./mvnw test`).
2. Start the application against Supabase; Flyway applies `V1` then `R__demo_data`.
3. Verify with SQL (tables only in `scheduler`, `btree_gist` in `extensions`, `anon` without `USAGE`)
   and re-run Supabase's Security Advisor.

Rollback (development only, no real data yet): `DROP SCHEMA scheduler CASCADE`, then restart.

## Open Questions

- Should the Data API be disabled in the Supabase dashboard as extra hardening? It is a manual
  setting for the project owner and does not affect this change.
