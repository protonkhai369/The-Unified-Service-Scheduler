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
