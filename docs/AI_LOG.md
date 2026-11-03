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

## 2026-10-31 - add-api-foundation (OpenSpec change)

Response conventions, one error pipeline (RFC 9457 problems with `code` and `requestId`),
`X-Request-Id` and access log, and the read-only catalog endpoints. Driven through OpenSpec with
Claude Code: explore, propose, human review of proposal/design/tasks, then `/opsx:apply`. The user
worked in Vietnamese; the artifacts and code are in English.

### Decisions taken with the user (explore and propose)

- No response envelope: a successful body is the resource itself. The user asked about a generic
  `ApiResponse<T>` wrapper. It was rejected because it would be a second error format beside
  problem+json, an "always 200" envelope hides failures from `http_server_requests_seconds`, and the
  name clashes with springdoc's `@ApiResponse`.
- Collections are `{"items": [...]}`; `type` is `about:blank` and clients branch on `code`; the scope
  is foundation plus catalog, so the error layer is proven over real endpoints.
- The user asked for the error catalogue as an enum: `ErrorCode` holds each code's status and
  default detail, plus the status-to-fallback rule (`forStatus`). The name stays `ErrorCode` rather
  than `ErrorEnum` (renaming was offered).
- During apply, the user asked to split the catalog into one service per resource:
  `ServiceTypeService`/`ServiceTypeController` and `DealershipService`/`DealershipController`, with
  one file per response record. The user then asked for layer packages; asked where (inside each
  feature or shared at the project root), they chose inside each feature, so `catalog` now has
  `controller`, `service`, `repository`, `entity` and `dto/response`, and the project stays packaged
  by feature at the top level (recorded in AGENTS.md and config.yaml). After an explanation of why
  `web` has two error entry points sharing one problem builder, the user had `web` split by
  function into `web.error`, `web.filter` and `web.dto`. Pure refactors; the catalog tests passed unchanged before and after, and were themselves split into
  `ServiceTypeIntegrationTest` and `DealershipIntegrationTest` with the same test methods.
- Booking rules settled in explore, deferred to the booking change: start times on the slot grid
  (`START_TIME_NOT_ON_GRID`), a server-generated idempotency key when the header is missing, a replay
  of a cancelled appointment returns its current state, no cancellation after the start time
  (`APPOINTMENT_ALREADY_STARTED`), `VEHICLE_ALREADY_BOOKED` as 409. The archived
  `add-scheduler-database` proposal said the `Idempotency-Key` header would become required; the
  user's later choice (optional, server-generated) supersedes it.

### Decisions taken by the AI

- `NOT_ACCEPTABLE` (406) dropped from the proposal: a client that refuses JSON cannot be promised a
  problem+json body either; such responses fall under `REQUEST_REJECTED`.
- Fixed `en` locale (`spring.web.locale-resolver: fixed`), so validation messages do not follow
  `Accept-Language` or this machine's Vietnamese locale.
- The shared test base runs a real server (`RANDOM_PORT`), because MockMvc never performs the
  container's error dispatch to `/error`. Probe endpoints live in a configuration the base imports,
  so all integration tests still share one context: the full run shows one PostgreSQL container and
  one Tomcat start.
- ECS structured logging is checked manually rather than in a test: reconfiguring logging inside the
  shared context would disturb every other test's output capture.

### Problems found during implementation

- **Spring Framework 7 omits the problem `type`.** `ProblemDetail.type` now defaults to `null` and is
  left out of the JSON (Spring 6 defaulted to `about:blank`). Found by
  `unknownPathIsProblemWithEveryMember` failing with "No value at JSON path $.type"; confirmed by
  disassembling `ProblemDetail`. `Problems.create` sets it explicitly.
- **`hibernate.jdbc.time_zone: UTC` corrupted opening hours.** The first catalog run returned `opens`
  `16:00` for a dealership stored as `08:00`. Root cause, shown with a six-line Java program: the
  setting makes Hibernate read `time` values as UTC on 1970-01-01 and convert them to the JVM zone,
  and this machine's zone `Asia/Saigon` was UTC+8 in 1970 (UTC+7 today), so 08:00 became 16:00. The
  setting (from the skeleton) is removed with an explanatory comment; `Instant` maps to `timestamptz`
  without it. The booking change must prove an `Instant` round trip on this non-UTC machine.
- **The custom `/error` controller leaked into the OpenAPI document** with every HTTP method (Boot's
  own error controller is hidden by springdoc; a replacement is not). Found by
  `OpenApiIntegrationTest`; fixed with `@Hidden`.
- **The spec-coverage guard passed before any implementation**, because the codes were already in the
  specs. Proven able to fail by temporarily adding an unspecified constant `UNSPECIFIED_PROBE`:
  `ErrorCodeTest` failed with "a spec under openspec/ defining `UNSPECIFIED_PROBE`"; the constant was
  removed again.
- Every other new test was watched failing for the expected reason first (missing header, missing
  `code`, Boot's own `/error` JSON, 404 for the catalog endpoints before they existed).
  `OpenApiIntegrationTest` was written after the endpoints, as a regression guard.

### Verification

- `./mvnw test`: `Tests run: 93, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (54 existing,
  39 new).
- Manual run against Supabase (`./mvnw spring-boot:run`, read-only requests, readiness `UP`):
  - with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, all 33 log lines were JSON, and
    `GET /api/v1/dealerships` with `X-Request-Id: demo-1` logged
    `{"message": "GET /api/v1/dealerships -> 200 (823 ms)", "requestId": "demo-1", ...}`;
  - without it, the console line read
    `... [demo-1] com.keyloop.scheduler.web.AccessLog : GET /api/v1/dealerships -> 200 (862 ms)`;
  - the demo dealerships came back with `opens` `08:00`/`07:30` and `closes` `18:00`/`17:30`;
  - `GET /api/v1/dealerships/999` returned `404` with `type` `about:blank`, `title` `Not Found`,
    `detail` "Dealership 999 does not exist.", `instance`, `code` `DEALERSHIP_NOT_FOUND` and
    `requestId` `demo-2`;
  - `/swagger-ui/index.html` returned 200.

| Spec scenario | Evidence |
| --- | --- |
| api-conventions: Single resource | `DealershipIntegrationTest.returnsDealershipWithItsOwnBaysAndTechniciansIncludingInactiveOnes` |
| api-conventions: Failure is never a 200 | `DealershipIntegrationTest.unknownDealershipIsNotFoundProblem` |
| api-conventions: Non-empty / Empty collection | `ServiceTypeIntegrationTest.listsServiceTypesInIdOrderWithTheirDurations`, `returnsAnEmptyCollectionAsEmptyItems` |
| error-responses: Unknown resource | `DealershipIntegrationTest.unknownDealershipIsNotFoundProblem`; manual run |
| error-responses: Framework error | `ErrorHandlingIntegrationTest.unknownPathIsProblemWithEveryMember` |
| error-responses: Request id in the body matches the header | `unknownPathIsProblemWithEveryMember`, `reportsFailuresOutsideSpringMvcAsProblems` |
| error-responses: Failure before any endpoint is reached | `ErrorHandlingIntegrationTest.reportsFailuresOutsideSpringMvcAsProblems` |
| error-responses: Value of the wrong type / Body that is not JSON | `namesTheFieldHoldingAValueOfTheWrongType`, `rejectsBodyThatIsNotJson` |
| error-responses: Malformed path variable | `DealershipIntegrationTest.nonNumericDealershipIdIsValidationProblem` |
| error-responses: Missing query parameter or header | `namesMissingQueryParameter`, `namesMissingHeader` |
| error-responses: Wrong method / Unsupported content type / Other client error | `ServiceTypeIntegrationTest.serviceTypesRejectOtherMethodsListingGet`, `rejectsUnsupportedContentType`, `keepsTheStatusOfOtherClientErrors`; `ErrorCodeTest` |
| error-responses: Unexpected exception (unchanged requirement) | `hidesUnexpectedExceptionsAndLogsTheirStackTrace` |
| dealership-catalog: Service types with durations | `ServiceTypeIntegrationTest.listsServiceTypesInIdOrderWithTheirDurations` |
| dealership-catalog: Dealership summary / Opening hours format | `listsDealershipsWithTheirZoneAndOpeningHours`; manual run on demo data |
| dealership-catalog: Technician qualifications visible / Inactive qualification not listed | `listsOnlyActiveQualificationsInAscendingOrder` |
| dealership-catalog: Inactive resources still listed / Only the dealership's own resources | `returnsDealershipWithItsOwnBaysAndTechniciansIncludingInactiveOnes` |
| dealership-catalog: Unknown dealership / Id that is not a positive integer | `unknownDealershipIsNotFoundProblem`, `nonNumericDealershipIdIsValidationProblem`, `nonPositiveDealershipIdIsValidationProblem` |
| observability: Caller-supplied id is propagated / Unsafe id is replaced | `RequestIdIntegrationTest`, `RequestIdFilterTest` |
| observability: Request logged | `RequestIdIntegrationTest.writesOneAccessLogLineCarryingTheRequestId`; manual run |
| observability: Container deployment (ECS) | manual run with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` |

Gaps:
- "Business error" (`NO_AVAILABILITY`) and the booking-named "Several invalid fields" scenario are
  shown only through equivalents (the `ApiException` path and the probe body) until the booking
  change.
- The ECS scenario names docker compose, which does not exist yet; it was checked with the
  environment variable on `spring-boot:run` and is not automated.
- Swagger UI's rendering was not inspected visually: the page is served and the OpenAPI document
  holds the three paths and their response schemas.
- The dealership detail's fixed number of queries (four) holds by construction; no test counts
  statements.

## 2026-11-02 - add-appointment-booking (OpenSpec change)

Booking, reading and cancelling appointments (Scenario A core requirements 1-3), the pure
`scheduling` domain, idempotency and booking metrics. Driven through OpenSpec with Claude Code:
explore, propose, human review, then `/opsx:apply` with tests written before each implementation
task. The user worked in Vietnamese; the artifacts and code are in English.

### Decisions taken with the user (explore and propose)

- The user asked why there is no `ApiResponse` wrapper; the reasons recorded in `add-api-foundation`
  were explained again and the decision was kept.
- Cancelling an appointment that has started is `409 APPOINTMENT_ALREADY_STARTED` (the request is
  valid; the resource's state forbids it), not `422`.
- Cancellation and the booking metrics are in this change; the availability search is the next one.
- `scheduler.max-booking-attempts` is 5 (the AI proposed 3).
- The user asked how to stay correct and fast under a burst. The AI showed that deterministic
  allocation (lowest bay, lightest technician) makes concurrent requests pick the same pair, so a
  retry-only design confirms one booking per round and answers `SLOT_CONTENTION` while bays are still
  free. Of three options (retry only, random choice on retry, a queue per dealership and local day),
  the user chose the queue: `pg_advisory_xact_lock` in front of the exclusion constraints, which stay
  the guarantee.
- The user asked about conflicts between time zones. The rules agreed, all written into the spec:
  - instants are used inside the application;
  - the dealership's zone is used only for opening hours, the slot grid and the local day;
  - a client at `+05:45` is checked on the dealership's clock;
  - one vehicle cannot be booked at two dealerships at the same instant, while the same clock time
    in two zones is not a conflict.
- Settled in the earlier explore and now specified:
  - the replay check runs before the start-time rules;
  - a database conflict first looks the idempotency key up, so a duplicate request that collided
    with its original on the vehicle is replayed rather than reported as `VEHICLE_ALREADY_BOOKED`.

### Decisions taken by the AI

- Rule order: in the past, then outside opening hours, then off the grid ("we are closed then" says
  more than "pick another minute").
- A lock timeout (`55P03`) ends the booking with `SLOT_CONTENTION` without retrying.
- Cancellation is one conditional `UPDATE`, so concurrent cancellations both answer `200` and no
  optimistic-lock exception can become a `500`.
- The spec scenario "Parallel requests exceeding capacity" was tightened from "`NO_AVAILABILITY` or
  `SLOT_CONTENTION`" to exactly 18 `NO_AVAILABILITY`, which the queue makes deterministic.
- The observability spec already required `http_server_requests_seconds` histogram buckets, which
  were not configured; this change adds them.
- "Now" comes from a `Clock` bean. Tests use a `MutableClock` registered by `ProbeConfiguration`, so
  the context stays shared (one container, one Tomcat for the whole suite).
- The manual demo ran on a throwaway PostgreSQL container, not on Supabase, so the 20-request burst
  left no rows in the shared demo database. This deviates from task 9.4, which said to cancel them.

### Problems found during implementation

- **Deadlock between two dealerships (found by a test).**
  - Symptom: `concurrentBookingsOfOneVehicleAtTwoDealerships` failed with a `500`. The log showed
    `40P01 deadlock detected ... while checking exclusion constraint`.
  - Cause: two bookings of one vehicle at two dealerships do not share a lock. Each inserted its GiST
    index entry and then waited for the other's exclusion check.
  - Fix: the deadlock victim is a lost race, so `BookingFailures` now retries it, and the retry
    answers `VEHICLE_ALREADY_BOOKED`.
  - Test: `BookingFailuresTest` covers the classification with hand-built PostgreSQL errors. The
    integration test hits the deadlock only when both inserts coincide (1 of 5 repeated runs).
  - Recorded in design.md: `booking-lock-timeout` (3 s) must stay above PostgreSQL's
    `deadlock_timeout` (1 s).
- **Stale classes from the IDE.** `ErrorCodeTest` reported 6 tests after a seventh was added.
  `javap` showed the class in `target/test-classes` without the new method and with Eclipse-style
  lambda names. VS Code's Java extension compiles into `target/`, and its newer timestamps made
  Maven skip recompiling. Every verification run since then uses `./mvnw clean test`.
- **Body errors under method validation.** The `Idempotency-Key` header carries a `@Pattern`, so
  Spring MVC validates the `@Valid` body as part of method validation. It then reports the body's
  errors as `ParameterErrors` inside `HandlerMethodValidationException`, and the handler would have
  named them after the Java parameter (`request`). `GlobalExceptionHandler` now names each body
  field, as the error-responses spec requires (`severalInvalidFields`, `missingField`).
- **OpenAPI documented the booking as `200`.** springdoc cannot see the status inside a
  `ResponseEntity`; `@ResponseStatus(CREATED)` documents the `201`. The extended
  `OpenApiIntegrationTest` caught it.
- **PostgreSQL driver scope.** Reading the violated constraint from `PSQLException` needs the driver
  at compile scope; it was `runtime`.
- **Test-side binding.** pgjdbc cannot infer a SQL type for `java.time.Instant` passed through
  `JdbcClient`, so the test passes an `OffsetDateTime`. Hibernate binds `Instant` itself, so
  production queries are unaffected.
- **Demo script error.** The first manual burst also sent three junk requests, because `psql`'s
  `INSERT 0 20` status line was read as vehicle ids (two `400`s, one duplicate vehicle). The burst was
  rerun with exactly 20 ids; only the clean run is reported below.

### Verification

- `./mvnw clean test`: `Tests run: 203, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (96 before
  this change, 107 new).
  - The whole run used one Tomcat start and one PostgreSQL container.
  - The two `500` log lines in the run come from the error-pipeline probes
    (`secret internal detail`, `secret filter detail`).
- `./mvnw test -DargLine=-Duser.timezone=Asia/Kathmandu`: 203 passed, with log timestamps at
  `+05:45`. So no code reads the JVM's zone, and `Instant` round-trips on a non-UTC JVM.
- `BookingConcurrencyIntegrationTest` five times in a row: 7/7 each time, 26.9-36.9 s per run
  including context start. Per test in the last run:
  - 20-request burst: 0.68 s;
  - lock-timeout test: 2.4 s;
  - lost race: 2.3 s;
  - one vehicle at two dealerships: 1.4 s (the run that deadlocked).
- `openspec validate add-appointment-booking --strict` and `openspec validate --all --strict`: valid.
- Manual run (`spring-boot:run` on a throwaway `postgres:17-alpine` with the demo data):
  - `POST` for Duc's Focus at Saigon, `2026-11-03T10:00:00+07:00`, `Idempotency-Key: demo-1` →
    `201`, `Location: /api/v1/appointments/9c99d593-...`, Bay B and Tran Thi Binh, `startTime`
    `2026-11-03T10:00:00+07:00`, `endTime` `10:45:00+07:00`, `timeZone` `Asia/Ho_Chi_Minh`;
  - the same request again → `201` with `Idempotent-Replayed: true` and the same id;
  - the same key with 11:00 → `422 IDEMPOTENCY_KEY_REUSED`;
  - cancel → `200` `CANCELLED`; cancel again → `200`; unknown id → `404`;
  - 20 parallel requests for 20 vehicles at London 11:00Z (two qualified technicians) → `2 × 201`,
    `18 × 409 NO_AVAILABILITY`, 1.5 s wall time including `curl` start-up; bays 1 and 2 with two
    different technicians;
  - `/actuator/prometheus`: `scheduler_bookings_total` confirmed 5, replayed 1, rejected 1,
    no_availability 37, contention 0; `scheduler_booking_conflicts_total` 0;
    `http_server_requests_seconds_bucket` present;
  - Swagger UI served (`200`); `/v3/api-docs` lists the three appointment paths, `201` for the
    booking and the optional `Idempotency-Key` header.

| Scenario | Test |
| --- | --- |
| Book a service appointment: Successful booking / End time derived from duration | `AppointmentBookingIntegrationTest.successfulBooking`, `endTimeDerivedFromServiceDuration` |
| Bay and technician available: all seven scenarios | `resourceBusyForPartOfThePeriod`, `baysFreeButNoQualifiedTechnicianFree`, `unqualifiedOrInactiveTechniciansAreNeverAllocated`, `inactiveBaysAreNeverAllocated`, `resourcesOfAnotherDealershipAreNeverAllocated`, `backToBackBookingsShareResources`, `cancelledAppointmentsDoNotOccupyResources` |
| No double booking: Parallel requests exceeding / within capacity | `BookingConcurrencyIntegrationTest.parallelRequestsExceedingCapacity`, `parallelRequestsWithinCapacity` |
| No double booking: Concurrent requests in different offsets | `concurrentRequestsInDifferentOffsets` |
| No double booking: Waiting too long for its turn | `waitingTooLongForItsTurn` |
| No double booking: Lost race is retried on fresh data | `lostRaceIsRetriedOnFreshData` (deterministic: uncommitted writer, `pg_stat_activity`) |
| No double booking: Overlapping insert bypassing the application | existing `AppointmentConstraintsIntegrationTest` (bay overlap, `23P01`) |
| Booking request validation: all twelve scenarios and rule precedence | `BookingValidationIntegrationTest` (one test each, plus `unknownReferenceIsReportedBeforeStartTimeRules`) |
| Technician workload balancing: four scenarios | `lighterDayWins`, `cancelledWorkDoesNotCount`, `tiesBrokenByTechnicianId`, `lowestFreeBayWins` (+ `nextBayWinsWhenTheFirstIsBusy`) |
| Confirmed appointment record: four scenarios | `AppointmentQueryIntegrationTest` |
| Idempotent booking retries: six sequential scenarios | `IdempotencyIntegrationTest` |
| Idempotent booking retries: Concurrent duplicates | `BookingConcurrencyIntegrationTest.concurrentDuplicates` |
| Appointment cancellation: five scenarios | `AppointmentCancellationIntegrationTest` |
| One booking per vehicle at a time: three sequential scenarios / concurrent | `sameVehicleAtTheSameTime`, `sameInstantAtDealershipsInDifferentTimeZones`, `sameClockTimeIsNotTheSameInstant` / `concurrentBookingsOfOneVehicleAtTwoDealerships`, `BookingFailuresTest.deadlockIsALostRace` |
| Booking times in the dealership's time zone: four scenarios | `clientInAnotherOffset`, `gridEvaluatedAtTheDealership`, `openingHoursFollowDaylightSavingTime`, `bookingOnADaylightSavingDay`; unit: `BusinessDayTest` |
| observability Booking metrics: six scenarios | `BookingMetricsIntegrationTest` (confirmed, replayed, rejected, `400` not counted, buckets); lost race in `lostRaceIsRetriedOnFreshData` |
| error-responses: Business error / Several invalid fields | `noAvailabilityCounted` and the `409` tests; `BookingValidationIntegrationTest.severalInvalidFields` |

Gaps:
- ~~A booking that ends in an unexpected `500` increases no `scheduler_bookings_total` series.~~
  Resolved before archiving: asked to choose between counting it as `rejected`, narrowing the spec
  or adding an outcome, the user chose a new `error` outcome. The spec delta, design and tasks were
  updated; `BookingMetricsIntegrationTest.unexpectedFailureCounted` failed first (`expected: 1.0 but
  was: 0.0`; the `500` came from `ZoneRulesException: Unknown time-zone ID: Mars/Olympus`), then
  passed. `./mvnw clean test`: 204 tests, 0 failures.
- Swagger UI's "Try it out" was not clicked; the page is served and booking was exercised with cURL.
- The deadlock path is reached by the integration test only when the two inserts coincide; the
  classification itself is covered deterministically by `BookingFailuresTest`.
- Expected 4xx booking outcomes are still logged at WARN (open question in design.md).

## 2026-11-02 - adopt-lombok-getters (OpenSpec change)

The user asked why the code has no mappers, builders, Lombok or MapStruct. The AI explained the
existing choice: records with static `of()` mappers, which it had taken over from `add-api-foundation`
without recording it as a decision. It offered three options, and the user chose the middle one: Lombok
`@Getter` on JPA entities only, through a small proposal.

### Decisions

- `@Getter` only. No `@Setter`: entities change state through factories and conditional updates.
  No `@Data`/`@EqualsAndHashCode`: a hash over mutable fields breaks entities in sets and Hibernate's
  identity. No `@ToString`. No `@Builder`: it would allow `Appointment` rows that bypass
  `confirmed(...)`, such as a new appointment created as `CANCELLED` or without timestamps.
- No MapStruct. The appointment mapping converts instants to the dealership's zone and combines three
  sources, so MapStruct would need Java code inside `expression` strings, which is longer than
  `of()` and not checked by the IDE.
- `TechnicianSkill` gets `@Getter` on `active` only. Class-level `@Getter` would add a public
  `getId()` that returns the key record, while its two id-derived getters stay hand-written.
- Lombok runs from an explicit `maven-compiler-plugin` processor path rather than with
  `-proc:full`, because JDK 23+ (this machine) skips processors found only on the class path, and a
  processor path runs Lombok and nothing else.
- The change has no spec delta, because it changes no externally observable behaviour. Inventing a
  requirement about Lombok would break the specs rule, so the change is to be archived with
  `--skip-specs`, and `openspec validate` on it reports the missing delta.

### Verification

- Before and after, `javap -public` listed the getters of all eight entities. They are identical (44),
  plus `Appointment.getVersion()` as designed. The generated methods carry `@lombok.Generated`.
- 42 hand-written getters were removed.
- `./mvnw clean test`: `Tests run: 204, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- `./mvnw -DskipTests package`: the repackaged jar holds 103 `BOOT-INF/lib` entries and no `lombok`
  entry.

## 2026-11-03 - Publish appointment booking from main

- Fetched `origin/main` and created `feat/appointment-booking` directly at
  `c11c95c`, preserving the pending booking and Lombok changes. The fetched main
  tree matched the former branch tip, so no merge or cherry-pick was needed.
- `openspec.cmd validate --all --strict`: 8 passed, 0 failed.
- Maven ran with JDK 23 outside the restricted sandbox. `mvnw.cmd test` reported
  204 tests, 0 failures, 154 errors, 0 skipped: Testcontainers could not find a
  valid Docker environment. Full integration verification is therefore blocked;
  the hosted database was not used. The other 50 tests passed.
- Removed trailing blank lines in two specs flagged by `git diff --check`.

## 2026-11-03 - add-availability-search (OpenSpec change)

`GET /api/v1/dealerships/{id}/availability?serviceTypeId=&date=`, the last specified capability
that was still missing. Driven through OpenSpec with Claude Code: explore, propose, human review
(which cut the test plan), then `/opsx:apply` with tests written before each implementation task.
The user worked in Vietnamese; the artifacts and code are in English.

### Problems found in the spec before implementation (explore)

- **A start equal to "now" would have been listed and then refused.** The spec said "MUST NOT list
  start times earlier than the current time", while booking rejects a start that is not after now
  (`START_TIME_IN_PAST`). At exactly 09:00:00, the 09:00 slot would have been offered and rejected
  with `422`. The rule is now "only start times after the current instant", and `SlotFinder` filters
  candidates with booking's own `StartTimeRule`, so the two cannot diverge.
- Slot times had no defined format. There was no time-zone scenario, although `openspec/config.yaml`
  requires one for anything touching availability. A past date had no defined answer, the two `404`s
  had no order, and the purpose claimed "bookable unless another request takes it first", which
  ignored `VEHICLE_ALREADY_BOOKED`.

### Decisions taken with the user (explore and propose)

- Slots carry `startTime` and `endTime` as `OffsetDateTime` with the dealership's offset, in the
  same form as an appointment, so `startTime` can be sent unchanged to the booking endpoint.
- A past date, or today once nothing can start, is `200` with empty `slots`, not `422`.
- Vehicle conflicts: only the spec's purpose is reworded; no `vehicleId` filter.
- The search calls the repositories itself rather than through a loader shared with booking. The
  round-trip test therefore became mandatory, because it is what notices if the two read different
  data.
- **Test plan cut.** The user asked why there were so many tests. The first task list had about 45,
  27 of them on Docker. It re-tested every domain rule over HTTP, re-tested booking's own `409`s,
  and gave `FreeResources` its own tests. The rule adopted: test each rule at the cheapest level where
  it lives, with exactly one test per spec scenario (table in design decision 7). The property test
  and the round-trip test were kept as the two proofs that search and booking agree.

### Decisions taken by the AI

- `FreeResources` was extracted from `Allocator`: the bays and technicians with no overlapping
  commitment. Allocation picks from it and the search counts it, so "free" has one definition.
  `Allocator`'s signature and `AllocatorTest` are unchanged, and `BookingAttempt` only gained a
  cross-reference comment.
- Candidates are generated on the local clock (`opens + k × step`) and kept once per instant, because
  a daylight saving gap moves a nonexistent local time onto an existing one.
- `READ COMMITTED`, not the `REPEATABLE READ` suggested during explore: every appointment is read by
  one statement, and catalog rows change only through migrations, so one snapshot already holds.
- No lock, no new metric, no new log event (reasons in design.md and `docs/SYSTEM_DESIGN.md`).
- A day whose closing instant has passed is answered without reading appointments. This also keeps
  absurd past dates away from the database.
- The property test checks every minute of each random day against `StartTimeRule` and `Allocator`.
  It does not re-enumerate the grid the way `SlotFinder` does, so it cannot share a generation bug.
  Its counts come from an overlap check written in the test.
- Left open (design.md): a date with an expanded year such as `+300000-01-01` parses, but lies
  beyond PostgreSQL's timestamp range and gives `500`. Booking's `startTime` has the same gap, so the
  fix belongs in a change that covers both.

### Problems found during implementation

- The background baseline run started before the `FreeResources` refactor was saved, but compiled it
  (58 main sources). It therefore verified the refactor (204/204, `AllocatorTest` 9/9) instead of
  the code before it. No separate pre-change run was made; `main` had been green when merged.
- The tests of group 3 and the OpenAPI test passed on their first run, because group 2 had already
  built the behaviour they check. Each was shown able to fail with a mutation (below), except the
  OpenAPI test, which springdoc satisfies from the controller.
- The lock mutation also failed `concurrentRequestsInDifferentOffsets`. The search blocked on the
  lock outlived its own test, and dealership ids restart at 1 after the per-test truncate. This
  happens only with the mutation, but it means that if `searchDuringABookingInProgress` ever fails, a
  second failure in a nearby concurrency test may be a consequence of the first.
- The plan estimated about 25 new tests, 13 of them on Docker. The result is 29: 12 unit and 17
  integration runs. The three `404`s are separate tests, and the parameterized `400` test runs three
  times.

### Verification

- `./mvnw clean test`: `Tests run: 233, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (204
  before, 29 new).
- `./mvnw test -DargLine=-Duser.timezone=Asia/Kathmandu`: 233 passed, with log timestamps at
  `+05:45`.
- Red before green:
  - `SlotFinderTest` against a stub returning no slots: 11 of 12 failed. The twelfth expects an empty
    list.
  - `AvailabilityIntegrationTest` before the endpoint existed: 12 of 12 failed with `404 NOT_FOUND`.
- `SlotFinderTest`: 12 tests in 0.35 s, including the 400 random cases.
- Mutations, each reverted afterwards (`diff` against a copy):

| Mutation | Tests that failed |
| --- | --- |
| `SlotFinder` ignores "now" (opening hours only) | property test, `sameDayQuery`, `queryAtAnExactSlotTime`, `todayAfterTheLastStart` |
| `SlotFinder` reports every bay instead of the free ones | property test, `remainingCapacityIsReported` |
| The search reads inactive bays too | `unqualifiedInactiveAndForeignResourcesAreNotCounted` |
| The search takes the dealership-day lock | `searchDuringABookingInProgress` (plus the follow-on failure above) |
| The search ignores the day's appointments | round-trip test, `aBookingRemovesTheSlotsItOverlaps` |

- Manual run: `spring-boot:run` on a throwaway `postgres:17-alpine` with the demo data. Flyway
  migrated a fresh schema, so Supabase was not used. On 2026-11-03 at 09:49Z:
  - Saigon, oil change (45 minutes), 2026-11-04: 19 slots, 07:30 to 16:30, all `2/2`;
  - booking the first slot's `startTime` unchanged gave `201`, Bay B and Tran Thi Binh;
  - searching again showed 07:30 and 08:00 at `1/1`, and 08:30 still at `2/2`;
  - today: London 15 slots from `10:00Z`; Saigon none, because 16:49 local is past the last start
    at 16:45;
  - `404 DEALERSHIP_NOT_FOUND` for dealership 99; `400 VALIDATION_FAILED` with an `errors` entry for
    `date` for `04/11/2026`;
  - cancelling restored `2/2`;
  - access-log lines took 15-20 ms (213 ms for the first, cold request); Swagger UI was served
    (`200`). The container was removed.
- `openspec validate add-availability-search --strict`: valid. `openspec validate --all --strict`:
  9 passed, 0 failed.

| Scenario | Test |
| --- | --- |
| Slots respect opening hours / Service longer than the step | `SlotFinderTest.slotsRespectOpeningHours`, `serviceLongerThanTheStep` |
| Response describes the context / Times in the dealership's offset | `AvailabilityIntegrationTest.responseDescribesTheContext`, `timesInTheDealershipsOffset` |
| Daylight saving day | `SlotFinderTest.daylightSavingDay` (instants), plus `aDaylightSavingGapInsideOpeningHoursYieldsNoDuplicateStart` |
| Fully booked / Remaining capacity / Bays free but no technician / Back-to-back | `SlotFinderTest.fullyBookedTimeIsHidden`, `remainingCapacityIsReported`, `baysFreeButNoQualifiedTechnicianFree`, `backToBackStartIsListed` |
| Unqualified, inactive and foreign resources / Cancelled appointments | `AvailabilityIntegrationTest.unqualifiedInactiveAndForeignResourcesAreNotCounted`, `cancelledAppointmentsDoNotOccupyResources` |
| Same-day query / Query at an exact slot time / Today after the last start | `SlotFinderTest.sameDayQuery`, `queryAtAnExactSlotTime`, `todayAfterTheLastStart` |
| Date in the past / Today is the dealership's date | `AvailabilityIntegrationTest.dateInThePast`, `todayIsTheDealershipsDate` |
| Validation: three `404` scenarios | `AvailabilityIntegrationTest.unknownDealership`, `unknownServiceType`, `unknownDealershipAndServiceType` |
| Validation: Missing service type / Non-positive service type / Malformed date | `AvailabilityIntegrationTest.rejectsAMissingOrMalformedParameter` [1]-[3] |
| A booking removes the slots it overlaps / Listed slot taken by another customer | `AvailabilityBookingIntegrationTest.aBookingRemovesTheSlotsItOverlaps`, `listedSlotTakenByAnotherCustomer` |
| Search during a booking in progress | `BookingConcurrencyIntegrationTest.searchDuringABookingInProgress` |
| Listed slot but the vehicle is busy | existing `AppointmentBookingIntegrationTest.sameInstantAtDealershipsInDifferentTimeZones` |
| (agreement with booking) | `SlotFinderTest.listsExactlyTheStartsBookingWouldAccept`, `AvailabilityBookingIntegrationTest.listsExactlyTheStartsThatBookingAccepts` |

Gaps:
- "Daylight saving day" is checked on instants. The `+01:00` formatting on that date is not checked
  over HTTP; the same conversion is checked with `+07:00` in `timesInTheDealershipsOffset`.
- Swagger UI's "Try it out" was not clicked. The page is served, the operation is documented (test),
  and the same request ran with cURL.
- In a fall-back overlap inside opening hours, the second occurrence of a local time is not offered
  (a documented risk; no dealership opens across a transition).
- The expanded-year `500` is an open question shared with booking.

### 2026-11-04 - Code review and comment cleanup (Codex)

- Reviewed the current working tree, including the existing uncommitted availability implementation.
  Preserved those changes. Removed comments from 93 Java files and seven project configuration,
  script and SQL files. Compared executable tokens/content against a pre-edit snapshot; Java
  strings and text blocks were preserved. Kept the applied V1 migration unchanged to preserve its
  Flyway checksum, and retained third-party Maven wrapper license headers and the shell shebang.
- New finding: `BookingAttempt.runInTransaction` does not recheck the start against the Clock after
  acquiring the advisory lock. A temporary Testcontainers HTTP probe held that lock, submitted a
  10:00 booking at 09:59:59, advanced the Clock to 10:00:01 and released the lock. The API returned
  201 and persisted `created_at > start_at`. Recommend rechecking after the replay lookup.
- Reproduced the previously documented expanded-year gap over HTTP: availability for
  `+300000-01-01` and booking at `+300000-01-01T10:00:00Z` both returned 500 with PostgreSQL
  timestamp-out-of-range errors. A shared supported date range needs a spec decision before fixing.
- Domain probe: London 2026-03-29 opening 01:00 and closing 02:00 resolve to the same instant;
  `BusinessDay.openingHours` throws instead of representing no usable hours. Separately confirmed
  the already documented fall-back second-occurrence omission in `SlotFinder`; this remains a
  known limitation, not a newly introduced regression. No behavioral fixes were applied.
- Verification: JDK 23 with cached Maven 3.10.0; 233 existing tests passed initially, then 235 tests
  passed after cleanup (233 existing plus two temporary diagnostic tests). The diagnostic tests
  asserted the observed bugs, not corrected behavior, and were removed after review.
  All database tests used PostgreSQL Testcontainers, never Supabase. OpenSpec strict validation:
  8 passed, 0 failed. `git diff --check` passed. `mvnw.cmd` itself still fails on a null `Target[0]`
  lookup in this environment, so verification invoked the cached Maven executable directly.

## 2026-11-03 - add-supabase-auth (OpenSpec change)

Login and authorisation with Supabase Auth: the backend became an OAuth2 resource server for
Supabase access tokens, with roles `customer`, `advisor` and `admin`. Driven through OpenSpec with
Claude Code: propose, human review, then `/opsx:apply` with tests written before each implementation
task. The user worked in Vietnamese; the artifacts and code are in English.

### Decisions taken with the user (propose)

- Three roles: customer, advisor (per dealership) and admin.
- Roles and links (`scheduler_role`, `customer_id`, `dealership_id`) live in Supabase
  `app_metadata`, not in a database table: no migration and no query per request.
- Public without a token: the catalog, Swagger UI with the OpenAPI document, and actuator health.

### Decisions taken by the AI

- Before writing the proposal, the AI fetched the live project's JWKS (HTTP 200, one `EC`/`ES256`/
  `P-256` key). So the backend verifies tokens with public keys and holds no Supabase secret, and
  `jws-algorithms: ES256` is mandatory (Spring defaults to RS256).
- Another user's appointment answers `404 APPOINTMENT_NOT_FOUND`, like an unknown id, so a response
  never confirms that it exists. A token without a usable role answers `403` on every appointment
  endpoint, because the spec separates "no permission at all" from "not yours".
- The booking permission check runs before the idempotency replay. A customer can put any
  `customerId` in the body, so a replay first would let anyone who knows another customer's key and
  request read that appointment. `BookingPermissionIntegrationTest` covers it.
- Cancelling checks visibility before the conditional `UPDATE`, otherwise a customer could cancel
  someone else's appointment and only then get a `404`.
- Deny by default: anything not listed as public needs a token, unknown paths included. The
  error-responses scenario "Framework error" was changed to an authenticated client.

### Changes to the design during implementation

- **Tests run the production decoder.** The design planned a `@Primary JwtDecoder` built from a test
  key, sharing a validator factory with production. Disassembling Boot 4.1.2's `JwtDecoderConfiguration`
  showed that it applies `JwkSetUriJwtDecoderBuilderCustomizer` beans before building the decoder.
  The tests therefore keep Boot's decoder (ES256, issuer, audience, expiry from `application.yml`),
  and a customizer only serves the test public key in place of the JWKS download. There is no
  production `JwtDecoder` or validator factory. As a result, removing `jws-algorithms: ES256` from
  `application.yml` makes the tests fail.
- **401 and 403 use the existing error path.** The entry point and the access-denied handler only call
  `sendError`. `ProblemErrorController` renders the problem on the error dispatch, and
  `ErrorCode.forStatus` maps 401 and 403. The design had them write the problem themselves.
- **Admin-only actuator.** Every actuator endpoint except health is admin-only, not just Prometheus and
  metrics.
- **Python demo-user script.** The script is standard-library Python, not bash. `jq` is not installed
  on this machine, and building JSON that holds a password with `printf` is unsafe.
- `design.md`, `tasks.md` and `proposal.md` were updated to match, and the change validates.

### Problems found during implementation

- Docker Desktop was not running, so the first red run failed at startup ("Could not find a valid
  Docker environment") rather than on assertions. Docker was started and the tests were run again.
- In the first red run, the authentication tests failed only on the missing `WWW-Authenticate`
  header, and requests without a token reached the controllers. Cause: the probe `SecurityFilterChain`
  in `ProbeConfiguration` was the only chain, which makes Boot drop its default chain. This was the
  expected red: there was no application chain yet.
- With security on but before the converter existed, an admin token got `403` on Prometheus: Spring's
  default converter maps `scope`, not `app_metadata`. `SupabaseJwtConverter` fixed it.
- The new console pattern `[requestId userId]` broke `logsClientErrorsWithoutStackTrace`, which
  expected `[warn-1]` on an authenticated request. The assertion now includes the test user's id.
- Bash heredocs holding long Python scripts failed in the agent's shell again. The scripts were
  written to the scratchpad and run from there.

### Verification

- `./mvnw clean test`: `Tests run: 253, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (204
  before this change, 49 new).
  - The whole run used one Spring context and one Tomcat start.
- Each implementation task had a red run first:
  - authentication: 7 of 11 failed;
  - converter and policy: did not compile;
  - booking permissions: 6 of 9 failed;
  - visibility: 6 of 10 failed;
  - OpenAPI and log line: 2 failed.
- Every scenario of `specs/access-control` (26) has a test whose `@DisplayName` is the scenario name,
  checked by a script. The modified error-responses scenarios are covered by
  `ErrorHandlingIntegrationTest.unknownPathIsProblemWithEveryMember`,
  `AuthenticationIntegrationTest.unknownPathWithToken` and `AuthenticationIntegrationTest.noToken`.
- `scripts/supabase-demo-users.py` ran twice against a local fake Admin API.
  - The first run created 4 users and the second updated the same 4.
  - Every request carried the secret key in both headers.
  - `app_metadata` held the expected role and id.
  - Neither the key nor the password appeared in the output.
- Live Supabase (task 7.2) is pending the user's go-ahead, because it creates real users. Until then,
  real Supabase tokens are verified only through the JWKS check made before the proposal.
- `openspec validate add-supabase-auth --strict`: valid.

## 2026-11-04 - Merge Supabase auth into availability search

The user merged `main` (PR #7, `add-supabase-auth`) into `feat/availability-search` and asked the AI
to handle the conflicts. Thirteen files conflicted.

### Decisions

- The conflicts had already been resolved in the working tree, by taking the auth side whole. That
  brought back every comment the availability branch had removed on purpose, as the user confirmed.
  The AI resolved the files again: auth's code with comment-only lines removed. A small script did
  it.
  - The script was checked first: applied to `main`, it reproduced the availability branch byte for
    byte in the 8 files where that branch had only removed comments.
  - `AppointmentApiTestSupport` was merged three ways, keeping the auth helpers (`bookAs`, `getAs`,
    `cancelAs`) and the availability helpers.
  - The previous resolution was kept in the scratchpad.
- The four documents had been merged sensibly, keeping both sections, and were kept as they were.
- Files that only auth added (package `security`, its tests and handlers) still carry their comments.
  Whether to strip them is left to the user.

### A gap found by the merge

`SecurityConfiguration` permitted `/api/v1/dealerships/**` for the catalog, so the availability
search, which did not exist when the rule was written, became public. The access-control spec says
only the catalog is public, so the code was wrong, not the spec. The user asked for the missing
authorisation to be added.
- Test first: `AuthenticationIntegrationTest.availabilitySearchNeedsAToken` (401 without a token,
  200 with a token of no role) and a bearer `security` entry on the operation in the OpenAPI test.
  Both were red.
- Fix: the catalog paths are listed one by one, and `AvailabilityController` has
  `@SecurityRequirement(name = "bearer")`. The availability test helpers send an admin token.
- The scenario "Availability search needs a token" was added to the access-control spec. The
  availability search needs no role, because it reveals no personal data.

### Verification

- The merge resolution, before the fix: `./mvnw clean test` 282 passed, 0 failed;
  `openspec validate --all --strict` 9 passed.
- With the fix: `./mvnw clean test` 283 passed, 0 failed (red first: 2 failures in the new
  assertions).
