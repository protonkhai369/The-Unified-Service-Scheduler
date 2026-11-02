## 1. Configuration and test support

- [x] 1.1 Add `SchedulerProperties` (`scheduler.max-booking-attempts` 5, `scheduler.slot-step` PT30M, `scheduler.booking-lock-timeout` PT3S) with defaults in `application.yml`, a UTC `Clock` bean, and `management.metrics.distribution.percentiles-histogram.http.server.requests: true`; write a binding test that checks the defaults and that a positive `slot-step` and attempt count are required
- [x] 1.2 Add a `@Primary` mutable test clock to `ProbeConfiguration`; reset it to `2026-11-02T00:00:00Z` in `DatabaseIntegrationTest` before each test; set `scheduler.booking-lock-timeout=2s` in the base test properties; confirm the existing suite still starts one context and one container
- [x] 1.3 Extend `DatabaseFixtures` with what booking tests need: a bookable dealership (zone, hours, bays, technicians with skills) in one call, an appointment with a chosen status, vehicle and idempotency key, and a JSON request builder for `POST /api/v1/appointments`

## 2. Scheduling domain (tests first)

- [x] 2.1 Write unit tests for `TimeRange` (half-open overlap: 09:00-10:00 and 10:00-11:00 do not overlap; minutes) and `BusinessDay`:
  - `Asia/Ho_Chi_Minh` 2026-11-03 07:30-17:30 is `00:30Z`-`10:30Z`;
  - `Europe/London` 2026-03-29 08:00-18:00 is `07:00Z`-`17:00Z`;
  - `containing` picks the local date, not the UTC date;
  - the grid holds for `08:45+05:45` at a Saigon dealership (10:00 local) and fails for `10:00+05:45` (11:15 local) and for a start with non-zero seconds
- [x] 2.2 Write unit tests for `StartTimeRule`: `start == now` is `IN_PAST`; 17:00 + 90 min at an 18:00 close and 07:00 at an 08:00 opening are `OUTSIDE_OPENING_HOURS`; 10:15 is `NOT_ON_GRID`; 07:45 at an 08:00 opening is `OUTSIDE_OPENING_HOURS` (rule order)
- [x] 2.3 Write unit tests for `Allocator`: the lowest free bay wins; the technician with the fewest booked minutes that day wins; ties go to the lowest id; a pair busy for part of the range is skipped; back-to-back commitments do not block; no free bay or no free technician gives empty
- [x] 2.4 Implement `TimeRange`, `BusinessDay`, `StartTimeRule`/`StartTimeViolation`, `Commitment`, `Allocation` and `Allocator` in package `scheduling`, with no Spring or JPA imports; make 2.1-2.3 pass

## 3. Persistence (tests first)

- [x] 3.1 Map the `Customer` and `Vehicle` entities (read-only, ids as plain values) and `Appointment` (UUID id assigned by the application, `Long version`, every V1 column except the generated `period`); confirm `contextLoads` passes with `ddl-auto: validate`
- [x] 3.2 Write `AppointmentRepositoryIntegrationTest`:
  - the day-commitments query returns only `CONFIRMED` rows of the given dealership with `start_at` inside the local day (Saigon 2026-11-03 = `2026-11-02T17:00Z` to `2026-11-03T17:00Z`);
  - the vehicle-overlap query finds a conflict at another dealership and none for a back-to-back range;
  - the key lookup returns the stored fingerprint;
  - the conditional cancel update changes 1 row for a future confirmed appointment and 0 rows for a cancelled or started one;
  - a persisted appointment reads back as the same instants
- [x] 3.3 Implement `CustomerRepository`, `VehicleRepository` and `AppointmentRepository` (day commitments, native `period &&` vehicle check, key lookup, conditional cancel, view query joining dealership, bay and technician by id) and the qualified-technician and active-bay queries; make 3.2 pass

## 4. Error codes

- [x] 4.1 Add the 13 `ErrorCode` constants from design decision 10, with detail templates that hold only client-sent values; extend `ErrorCodeTest` with a template check for `APPOINTMENT_NOT_FOUND` and `VEHICLE_NOT_OWNED_BY_CUSTOMER`; confirm every constant is found in a spec

## 5. Booking endpoint (tests first)

- [x] 5.1 Write `RequestFingerprintTest`: 64 lowercase hex characters; the same instant in `+07:00` and `Z` gives the same fingerprint; any differing parameter gives a different one
- [x] 5.2 Write `AppointmentBookingIntegrationTest` with one test per scenario of these requirements:
  - "Book a service appointment" (`201`, `Location`, every body field, end time from the duration);
  - "Bay and qualified technician available for the whole duration" (partial overlap, no qualified technician, inactive, unqualified or retired-skill technicians, inactive bay, another dealership's resources, back-to-back, cancelled appointment);
  - "Technician workload balancing" (four scenarios);
  - "Booking times in the dealership's time zone" (four scenarios);
  - "One booking per vehicle at a time" (the three sequential scenarios);
  - also the `error-responses` scenario "Several invalid fields" on the real booking body
- [x] 5.3 Write `BookingValidationIntegrationTest` with one test per scenario of "Booking request validation", plus rule precedence: an unknown customer together with a past start time gives `CUSTOMER_NOT_FOUND`
- [x] 5.4 Write `IdempotencyIntegrationTest` with one test per sequential scenario of "Idempotent booking retries": safe retry, the same instant in another offset, a retry after the start time (advance the test clock), replay of a cancelled appointment, a reused key, no key → `VEHICLE_ALREADY_BOOKED`
- [x] 5.5 Implement `BookAppointmentRequest` (`@NotNull @Positive` ids, `@NotNull OffsetDateTime startTime`), `AppointmentResponse`, `RequestFingerprint`, `BookingService` (steps ①-⑤ of design decision 3), `BookingAttempt` (`set_config('lock_timeout')`, `pg_advisory_xact_lock(hashtextextended(...))`, then steps 1-5 of design decision 4 in one `TransactionTemplate`), `BookingFailures` (design decision 5), and `POST /api/v1/appointments` (`Idempotency-Key` header with `@Pattern`, `Location`, `Idempotent-Replayed: true` on replays); make 5.1-5.4 pass

## 6. Concurrency (tests first)

- [x] 6.1 Write `BookingConcurrencyIntegrationTest`, firing requests from a thread pool behind a start latch:
  - 20 requests for 20 vehicles against 2 free pairs: exactly 2 `201`, 18 `NO_AVAILABILITY`, and an SQL check that no bay or technician has overlapping confirmed rows;
  - 4 requests against 4 free pairs: 4 `201` with distinct bays and technicians;
  - `+07:00` and `Z` for the same instant against 1 free pair: one `201`, one `NO_AVAILABILITY`;
  - two requests with the same key and body: one row, both responses carry its id;
  - one vehicle at two dealerships at the same instant: one `201`, one `VEHICLE_ALREADY_BOOKED`
- [x] 6.2 Write the deterministic lost-race test from design decision 11. A test connection inserts an uncommitted conflicting row on bay 1 (with technician B). The test waits until `pg_stat_activity` shows a backend waiting on a `Lock`, then commits. Expect `201` on bay 2 and `scheduler_booking_conflicts_total` increased by one
- [x] 6.3 Write the lock-timeout test: a test connection holds `pg_advisory_xact_lock` on the same dealership-day key for longer than 2 s; expect `409 SLOT_CONTENTION` and no row for that request
- [x] 6.4 Make 6.1-6.3 pass; run `BookingConcurrencyIntegrationTest` five times in a row to check that it is not flaky, and note its duration

## 7. Read and cancel (tests first)

- [x] 7.1 Write `AppointmentQueryIntegrationTest` for "Confirmed appointment record": the same data as at booking time, a cancelled appointment shows `CANCELLED`, an unknown id gives `404 APPOINTMENT_NOT_FOUND`, `abc` gives `400` with an `errors` entry for `id`
- [x] 7.2 Write `AppointmentCancellationIntegrationTest` for "Appointment cancellation": cancelling frees the slot (a new booking for that bay, technician and time is confirmed); cancelling twice gives `200`; cancelling at the start time (advance the clock) gives `409 APPOINTMENT_ALREADY_STARTED` and the appointment stays `CONFIRMED`; two concurrent cancellations both give `200`; an unknown id gives `404`
- [x] 7.3 Implement `AppointmentService` (the read through the view query; cancel through the conditional update, then a re-read to decide between `200`, `404` and `409`), `GET /api/v1/appointments/{id}` and `POST /api/v1/appointments/{id}/cancel` (`@PathVariable UUID id`, no `@Validated`); make 7.1-7.2 pass

## 8. Metrics and log events (tests first)

- [x] 8.1 Write `BookingMetricsIntegrationTest` for each scenario of the `observability` "Booking metrics" requirement (confirmed, replayed, rejected, a `400` not counted, latency buckets present in `/actuator/prometheus`), and use `OutputCaptureExtension` to assert the INFO confirmation line carries the request id and no customer name
- [x] 8.2 Implement `BookingMetrics` (`scheduler.bookings` with an `outcome` tag, `scheduler.booking.conflicts`) and the log events of design decision 9; make 8.1 and the conflict assertion of 6.2 pass
- [x] 8.3 Added at archive review: write the "Unexpected failure counted" test (a dealership whose stored time zone is not a valid IANA id answers `500`), then count unexpected failures as `scheduler_bookings_total{outcome="error"}`

## 9. OpenAPI, documentation and manual checks

- [x] 9.1 Extend `OpenApiIntegrationTest`: `/v3/api-docs` documents the three appointment paths and the optional `Idempotency-Key` header; open Swagger UI locally and book one appointment with "Try it out"
- [x] 9.2 Add a Booking section to `docs/SYSTEM_DESIGN.md`:
  - the flow diagram;
  - time handling;
  - the three protection layers, with the lock's rationale and throughput estimate;
  - the failure classification table;
  - the new error codes in the error table;
  - the proposal's assumptions in the Assumptions section
- [x] 9.3 Update `AGENTS.md` and `openspec/config.yaml`:
  - current state (booking, read and cancel exist);
  - the per-dealership-day advisory lock as the first layer, with the exclusion constraints still the guarantee;
  - "now" only from the `Clock` bean;
  - the test clock in `ProbeConfiguration`
- [x] 9.4 Run the application locally on the demo data and capture the cURL output of:
  - a booking;
  - a replay with the same key;
  - a reused key;
  - a cancellation;
  - a 20-request parallel burst with a script, plus its outcome counts;
  - the `scheduler_*` lines of `/actuator/prometheus`.

  Then cancel the demo appointments created
- [x] 9.5 Add the change to `docs/AI_LOG.md`:
  - the decisions taken in explore: 409 for a started appointment, cancellation and metrics in scope, 5 attempts, the dealership-day lock chosen over random retries, replay before the rules, the key lookup before classifying a conflict;
  - the problems found while implementing;
  - the results of 9.4 and 10.x

## 10. Verification

- [x] 10.1 Run `./mvnw test` (with `JAVA_HOME` pointing at JDK 21+) and quote the Surefire summary; every test passes
- [x] 10.2 Run the suite once on a non-UTC JVM with `./mvnw test -DargLine=-Duser.timezone=Asia/Kathmandu` and confirm it passes, which proves the `Instant` round trip and that no code reads the JVM zone
- [x] 10.3 Map every scenario in `specs/appointment-booking` and `specs/observability` "Booking metrics", plus the `error-responses` scenarios "Business error" and "Several invalid fields", to a passing test, and list any gap. "Overlapping insert bypassing the application" is covered by the existing `AppointmentConstraintsIntegrationTest`
- [x] 10.4 Run `openspec validate add-appointment-booking --strict` and confirm it passes
