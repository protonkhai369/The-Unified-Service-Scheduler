# AI Engineering & Verification Log

## 2026-10-31 - Prepare repository for publication

- Expanded `.gitignore` for environment variants (keeping `.env.example`), archived agent configuration from the unrelated project, runtime logs, JVM dumps, and editor/OS temporary files.
- Preserved the existing application skeleton, OpenSpec specifications, and shared agent tooling for the initial project commit.
- Verified ignore rules and checked candidate filenames and common credential patterns; no matches found outside excluded local files and skill examples.
- `openspec.cmd validate --all --strict`: 5 passed, 0 failed.
- Application tests were not run for this repository packaging task. The Docker probe was denied by the sandbox; no hosted database was used.
- Remote has no branches. Publish the existing README-only initial commit as `main`, then push the setup branch and open a pull request targeting `main`.

## 2026-10-31 - add-scheduler-database (OpenSpec change)

First Flyway migration (`V1__scheduler_schema.sql`), demo data (`R__demo_data.sql`) and the
integration tests that prove every database-enforced booking rule. Driven through OpenSpec:
explore and propose, human review of proposal/design/tasks, then `/opsx:apply` (Claude Code).

### Problems found before implementation (proposal and review)

- **`openspec/config.yaml` was silently ignored.** OpenSpec printed `Warning: could not parse
  openspec/config.yaml (Implicit map keys need to be followed by map values at line 63, column 7:);
  ignoring it.` The last `tasks` rule was an unquoted YAML scalar containing `: `, so the whole file
  was dropped and every proposal, spec, design and task instruction was generated without the
  project context or the per-artifact rules. Fixed by quoting that list item. Reproduced during apply
  by running OpenSpec on a scratch copy of the committed file; after the fix, the instructions for
  this change contain no warning and include the project context.
- **The previous schema had never run against a database.** Only pure unit tests existed, so none of
  its constraints had been proven. This change therefore starts with a spike on both targets and
  proves every constraint with an integration test that inserts rows directly with SQL.
- **`V100__demo_data.sql` would have broken the next migration.** With V100 applied, Flyway treats a
  later `V2__...` as a lower, unapplied (ignored) version and fails validation. Demo data became the
  repeatable `R__demo_data.sql`, guarded to insert only into an empty `dealership` table.
  `DemoDataIntegrationTest.laterVersionedMigrationStillApplies` applies a test-only `V2__probe.sql`
  after the demo data.
- **No guard against booking one vehicle twice.** The earlier design had exclusion constraints on
  bay and technician only, so the same car could be booked at the same time on two bays with two
  technicians. Added `appointment_no_vehicle_overlap`.
- **Twin-request race on the idempotency key.** Two identical requests arriving together can both
  miss a lookup-then-insert idempotency check and both book. The key is now a `UNIQUE` column written
  by the same `INSERT` as the appointment, so the database arbitrates and exactly one commits
  (`ConcurrentInsertIntegrationTest.sameIdempotencyKeyCommitsOnce`).

### Spike: the risky SQL on both targets (tasks 1.1, 1.2)

The design depends on three things no earlier code had ever executed: `btree_gist` installed in an
`extensions` schema, a generated `tstzrange(start_at, end_at, '[)')` column, and a partial
`EXCLUDE USING gist` constraint whose operator classes must resolve while `search_path` does not
include `extensions` (Flyway runs with `search_path` set to `scheduler`). A throwaway script (kept
out of the repository) ran the whole check inside one transaction and then rolled it back.

| | Plain PostgreSQL (`postgres:17-alpine`, 17.10) | Supabase session pooler (17.11) |
| --- | --- | --- |
| `extensions` schema | created | already existed (`NOTICE 42P06`, skipped) |
| `btree_gist` installed in | `extensions` | `extensions` |
| `search_path` during DDL | `spike_scratch` only | `spike_scratch` only |
| Operator classes picked by the GiST index | `gist_int8_ops` (`extensions`), `range_ops` (`pg_catalog`) | same |
| Back-to-back 10:00-11:00 / 11:00-12:00 `+07` | accepted | accepted |
| Overlapping `CANCELLED` row | accepted (partial predicate) | accepted |
| 03:30-04:30 `Z` (= 10:30-11:30 `+07`) | `23P01` on `booking_no_bay_overlap` | `23P01` on `booking_no_bay_overlap` |
| Left behind after `ROLLBACK` | nothing | nothing (`btree_gist` not installed, no scratch schema) |

Conclusion: Decision 9 of the design holds. Default operator classes are found by type and access
method, not through `search_path`, so V1 can install the extension in `extensions` and create its
tables with `search_path = scheduler` on both targets.

A read-only inventory of Supabase before V1: `public` contains no tables (the table flagged by the
Security Advisor on 2026-10-30 is gone), `scheduler` contains only `flyway_schema_history` with
Flyway's `<< Flyway Schema Creation >>` marker row, and `anon`, `authenticated` and `service_role`
hold no `USAGE` on `scheduler`.

### Implementation findings (tasks 2-4)

- **The design's generated column made one spec scenario unreachable.** Tests were written first and
  failed until V1 existed. With V1 written exactly as designed, 50 of 51 tests passed;
  `rejectsEmptyOrInvertedRange[11:00, 10:00]` failed with `expected "23514" but was "22000"`
  (`range lower bound must be less than or equal to range upper bound`). PostgreSQL computes stored
  generated columns before `CHECK` constraints, so `tstzrange()` raised a generic data exception
  before `appointment_time_order` could fire. The expression became
  `CASE WHEN start_at < end_at THEN tstzrange(start_at, end_at, '[)') END`, and design.md Decision 3
  was updated. The empty range (10:00 to 10:00) already produced `23514`.
- **The Supabase-only revoke block is tested on plain PostgreSQL.** Not a task, added because V1
  cannot be fixed once applied to Supabase. `SchemaPlatformIntegrationTest.revokesDataApiRolesWhenTheyExist`
  creates `anon`/`authenticated` roles in the container, pre-grants them `USAGE` and default
  privileges on a fresh `scheduler` schema in a scratch database, runs V1 and asserts that no
  schema, table or default privilege remains.
- **One context, one container, no demo data in tests.** `ServiceSchedulerApplicationTests` now
  extends `DatabaseIntegrationTest`, so every test shares one cached context with
  `spring.flyway.locations=classpath:db/migration`. The test log shows one
  `postgres:17-alpine` container and one application start for the whole suite, and
  `testDatabaseHasNoDemoData` still passes after `application.yml` gained `classpath:db/demo`.
  Flyway-level tests (demo data, revokes) use scratch databases in the same container, so they never
  touch the shared schema.
- The proposal's Impact section still named `V100__demo_data.sql`; corrected to `R__demo_data.sql`
  to match design and tasks.

### Applied to Supabase (tasks 5.1, 5.2, 5.4)

- `./mvnw spring-boot:run` against the session pooler: Flyway validated, then `Migrating schema
  "scheduler" to version "1 - scheduler schema"` and `with repeatable migration "demo data"`,
  `Successfully applied 2 migrations ... now at version v1`. `/actuator/health/readiness` returned
  `{"status":"UP"}` (HTTP 200).
- Read-only SQL afterwards: 0 relations in `public`; the nine tables (eight scheduler tables and
  `flyway_schema_history`) in `scheduler`; `btree_gist` 1.7 in `extensions`; `anon` and
  `authenticated` with no `USAGE`, no table grants and no default privileges on `scheduler`; the
  three exclusion constraints present; demo rows 2 dealerships, 4 service types, 5 bays,
  5 technicians (1 inactive), 13 qualifications, 2 customers, 3 vehicles.
- Restart: `Schema "scheduler" is up to date. No migration necessary.`; readiness `UP`; Flyway
  history unchanged at 3 rows.
- Supabase Security Advisor (task 5.3), checked by the project owner in the dashboard at 15:13,
  after V1 was applied at 15:01: 0 errors, 0 warnings, 0 suggestions ("No errors detected"), so the
  "RLS Disabled in Public" finding from 2026-10-30 is gone (screenshot shared in the session).

### Verification (tasks 7.1-7.3)

- `./mvnw clean test`: `Tests run: 54, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, one
  container for the whole suite.
- `openspec validate add-scheduler-database --strict`: `Change 'add-scheduler-database' is valid`;
  `openspec validate --all --strict`: 6 passed, 0 failed.

Spec scenarios and their evidence (`ACIT` = `AppointmentConstraintsIntegrationTest`):

| Scenario | Evidence |
| --- | --- |
| Malformed VIN rejected | `ReferenceDataIntegrationTest.rejectsMalformedVin` (I, O, Q, lower case, 16, 18 chars, space) |
| Duplicate VIN rejected | `ReferenceDataIntegrationTest.rejectsDuplicateVin` |
| Non-positive service duration rejected | `ReferenceDataIntegrationTest.rejectsNonPositiveDuration` |
| Closing time before opening time rejected | `ReferenceDataIntegrationTest.rejectsClosingBeforeOpening` |
| Valid appointment stored | `ACIT.AppointmentRecord.storesValidAppointment` |
| Empty or inverted time range rejected | `ACIT.AppointmentRecord.rejectsEmptyOrInvertedRange` (both cases) |
| Unknown status rejected | `ACIT.AppointmentRecord.rejectsUnknownStatus` |
| Dangling reference rejected | `ACIT.AppointmentRecord.rejectsDanglingReference` |
| Bay: partial overlap rejected | `ACIT.BayOverlap.rejectsPartialOverlap` |
| Bay: back-to-back allowed | `ACIT.BayOverlap.allowsBackToBack` |
| Bay: cancelled appointment does not block | `ACIT.BayOverlap.cancelledAppointmentDoesNotBlock` |
| Bay: overlap detected across offsets | `ACIT.BayOverlap.detectsOverlapAcrossOffsets` |
| Bay: concurrent conflicting inserts | `ConcurrentInsertIntegrationTest.secondOverlappingInsertFailsWhenFirstCommits`, `...SucceedsWhenFirstRollsBack` |
| Same technician on another bay rejected | `ACIT.TechnicianOverlap.rejectsSameTechnicianOnAnotherBay` |
| Different technicians at the same time allowed | `ACIT.TechnicianOverlap.allowsDifferentTechniciansAtTheSameTime` |
| Duplicate booking of a vehicle rejected | `ACIT.VehicleOverlap.rejectsDuplicateBookingOfVehicle` |
| Two vehicles of one customer at the same time allowed | `ACIT.VehicleOverlap.allowsTwoVehiclesOfOneCustomer` |
| Unqualified technician rejected | `ACIT.QualificationAndDealership.rejectsUnqualifiedTechnician` |
| Bay from another dealership rejected | `ACIT.QualificationAndDealership.rejectsBayFromAnotherDealership` |
| Technician from another dealership rejected | `ACIT.QualificationAndDealership.rejectsTechnicianFromAnotherDealership` |
| Used qualification cannot be deleted | `ACIT.QualificationAndDealership.usedQualificationCannotBeDeleted` |
| Duplicate key rejected | `ACIT.IdempotencyKey.rejectsDuplicateKey` |
| Missing or malformed key rejected | `ACIT.IdempotencyKey.rejectsMissingKey`, `rejectsMalformedKey`, `rejectsTooLongKey` |
| Concurrent inserts with the same key | `ConcurrentInsertIntegrationTest.sameIdempotencyKeyCommitsOnce` (non-overlapping variant, `23505`) |
| Nothing in the public schema | Supabase check (task 5.2); `SchemaPlatformIntegrationTest.publicSchemaStaysEmpty` on the container |
| Anonymous API role locked out | Supabase check (task 5.2); `SchemaPlatformIntegrationTest.revokesDataApiRolesWhenTheyExist` |
| No public-exposure advisor finding | Security Advisor after V1: 0 errors, 0 warnings, 0 suggestions (task 5.3) |
| Fresh plain PostgreSQL | every test (migrations on a fresh container); `ServiceSchedulerApplicationTests.contextLoads` |
| Supabase | Supabase run (task 5.1) |
| Restart is a no-op | Supabase restart (task 5.4); `DemoDataIntegrationTest.doesNotInsertDemoDataTwice` |
| Demo data on a fresh run | `DemoDataIntegrationTest.loadsDemoDataIntoEmptyDatabase`; Supabase (tasks 5.1, 5.2) |
| Demo data not duplicated | `DemoDataIntegrationTest.doesNotInsertDemoDataTwice` (restart and changed script) |
| Later schema migrations still apply | `DemoDataIntegrationTest.laterVersionedMigrationStillApplies` |
| Tests start clean | `SchemaPlatformIntegrationTest.testDatabaseHasNoDemoData` |

Gaps: the same-key race is tested only
where the two appointments do not overlap (`23505`); the overlapping variant, which the spec allows
to fail with `23P01` instead, is not exercised. "Fresh plain PostgreSQL ... becomes ready" is shown
by the context starting on the container; the readiness endpoint itself was checked only on Supabase.
