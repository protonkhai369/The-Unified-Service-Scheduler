## Why

Scenario A's core requirements 2 and 3 (a service bay and a qualified technician free for the whole
service duration; a persisted appointment linking customer, vehicle, technician and bay) need a data
model that stays correct under concurrent bookings. After the 2026-10-30 reset there is no schema at
all, the previous schema was never actually executed against a database, and the move to Supabase
showed that tables in `public` are exposed to anonymous callers through Supabase's Data API. This
change lays the database foundation every later feature (booking, availability, catalog) builds on.

## What Changes

- Add the first Flyway migration creating the scheduler data model in a dedicated `scheduler`
  schema: dealership, service type, service bay, technician, technician skill, customer, vehicle and
  appointment.
- Enforce the core booking invariants inside PostgreSQL rather than in application code:
  - no two confirmed appointments overlap for the same service bay, technician, or vehicle
    (`EXCLUDE USING gist` on a half-open time range; cancelled appointments free their resources);
  - an appointment's technician is qualified for its service type, and its bay and technician belong
    to its dealership (composite foreign keys);
  - every appointment carries a unique idempotency key and a request fingerprint.
- Make migrations run unchanged on Supabase and on plain PostgreSQL 17 (Testcontainers):
  `btree_gist` in an `extensions` schema, Supabase-only privilege revokes guarded by role checks.
- Keep the schema out of Supabase's public Data API: no application object in `public`, and the
  anonymous/authenticated roles have no access to `scheduler`.
- Add demo reference data (dealerships, bays, technicians, skills, customers, vehicles) as a separate
  migration location that the local and Supabase runs load but tests never do.
- Prove every invariant with integration tests that insert rows directly with SQL on a throwaway
  PostgreSQL 17 container.

## Capabilities

### New Capabilities

- `scheduling-data-model`: the persisted entities and the integrity rules the database guarantees
  for them (overlap prevention for bays, technicians and vehicles; qualification and dealership
  consistency; idempotency key storage).
- `database-platform`: where and how the data lives: isolated `scheduler` schema invisible to
  Supabase's Data API, portable versioned migrations, and demo data kept apart from schema and tests.

### Modified Capabilities

None. The API-level consequences of this design (the `Idempotency-Key` header becoming required,
the new `VEHICLE_ALREADY_BOOKED` error) change `appointment-booking` and belong to the follow-up
booking change that exposes them over HTTP.

## Non-goals

- REST endpoints, JPA entities, the allocation/retry logic and the idempotency HTTP flow (next change).
- Availability search and catalog APIs.
- A separate idempotency table with per-client scoping, stored responses or TTL.
- Technician shifts and time off, bay types, per-weekday opening hours and holidays.
- Row Level Security policies: the schema is not exposed to the Data API, so there is nothing for
  them to protect.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- A technician is available when they have no overlapping confirmed appointment.
- Any active service bay can host any service type.
- A dealership has the same opening hours every day and no holidays.
- Idempotency keys are global (there is no client identity yet) and live as long as their appointment.

## Impact

- New: `src/main/resources/db/migration/V1__scheduler_schema.sql`,
  `src/main/resources/db/demo/R__demo_data.sql`, schema integration tests under `src/test/java`,
  `docs/SYSTEM_DESIGN.md` (data model and assumptions), `docs/AI_LOG.md`.
- Changed: `src/main/resources/application.yml` (Flyway locations), test configuration so tests load
  only `db/migration`.
- Supabase project: creates the `extensions` schema if missing, installs `btree_gist` there, and
  creates all tables in the existing `scheduler` schema. Nothing is created in `public`.
- No new dependencies.
