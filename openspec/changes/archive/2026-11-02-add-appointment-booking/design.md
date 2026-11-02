## Context

- V1 already defines `appointment`:
  - a UUID id, stored `start_at`/`end_at`, and a generated half-open `period`;
  - status `CONFIRMED`/`CANCELLED`, a unique idempotency key, a SHA-256 request fingerprint, and a
    `version` column;
  - three exclusion constraints (`appointment_no_bay_overlap`,
    `appointment_no_technician_overlap`, `appointment_no_vehicle_overlap`) and the composite foreign
    keys that keep the bay, the technician and the qualification consistent with the dealership;
  - the partial index `appointment_dealership_start_idx (dealership_id, start_at) WHERE status =
    'CONFIRMED'`.
- `ConcurrentInsertIntegrationTest` already proves that the constraints hold for concurrent,
  uncommitted inserts at `READ COMMITTED`.
- Existing pieces this change builds on:
  - the catalog entities (dealership, service type, bay, technician, skill) and their repositories;
  - the error pipeline: `ErrorCode`, `ApiException`, `GlobalExceptionHandler` (4xx logged at WARN,
    5xx at ERROR);
  - the request id filter and access log;
  - `DatabaseIntegrationTest`: one context, one container, real Tomcat, a `RestTestClient`, and
    fixtures that can insert appointments directly.
- `customer` and `vehicle` have no entities yet. `scheduler.*` properties do not exist. There is no
  `Clock` bean.
- `pom.xml` already contains Micrometer, the Prometheus registry and the PostgreSQL driver.
- Hikari is capped at 5 connections (`DB_POOL_SIZE`), for Supabase's session pooler, and tests use
  the same cap.

## Goals / Non-Goals

**Goals:**
- Book, read and cancel appointments as specified in `specs/appointment-booking`. A double booking
  must be impossible; the database is the guarantee.
- Under a burst of requests for one dealership, every request that could be served is served. The
  rest get a definite `NO_AVAILABILITY` rather than `SLOT_CONTENTION`.
- Keep the scheduling rules in a pure domain package, unit-tested without Spring, that availability
  search can reuse.
- Make bookings measurable (outcomes, lost races, latency buckets) and traceable in logs.

**Non-Goals:** the availability endpoint, appointment search or rescheduling, customer and vehicle
endpoints, shifts and holidays, authentication, push notifications, Docker, and the README rewrite
(see the proposal).

## Decisions

### 1. Packages and responsibilities

```
scheduling/            pure domain: no Spring, no JPA, no ApiException
  TimeRange            [start, end) of Instants; overlaps(), minutes()
  BusinessDay          local date + ZoneId + opens/closes; containing(instant), openingHours(), isOnGrid(start, step)
  StartTimeRule        check(wanted, now, day, step) -> Optional<StartTimeViolation>
  StartTimeViolation   IN_PAST, OUTSIDE_OPENING_HOURS, NOT_ON_GRID
  Commitment           (bayId, technicianId, range): a confirmed appointment that day
  Allocator            allocate(wanted, bayIds, technicianIds, dayCommitments) -> Optional<Allocation>
  Allocation           (bayId, technicianId)

appointment/
  controller/AppointmentController   POST /api/v1/appointments, GET /{id}, POST /{id}/cancel
  service/BookingService             the booking flow (decision 3), not @Transactional
  service/BookingAttempt             one attempt inside one transaction (decision 4)
  service/BookingFailures            database error -> outcome (decision 5)
  service/AppointmentService         read and cancel
  service/BookingMetrics             counters (decision 9)
  service/RequestFingerprint         SHA-256 of the normalised parameters (decision 6)
  repository/AppointmentRepository   day commitments, vehicle overlap, key lookup, cancel, view query
  entity/Appointment                 maps every V1 column except the generated `period`
  dto/request/BookAppointmentRequest
  dto/response/AppointmentResponse   (+ ResourceRef {id, name} for bay and technician)

customer/
  entity/Customer, entity/Vehicle    read-only, ids as plain values
  repository/CustomerRepository, VehicleRepository

config/
  SchedulerProperties                maxBookingAttempts=5, slotStep=PT30M, bookingLockTimeout=PT3S
  ClockConfiguration                 Clock.systemUTC(); tests replace it with a controllable clock
```

The domain returns values, never exceptions. The services translate a `StartTimeViolation` or an
empty `Allocation` into `ApiException(ErrorCode.X)`, as AGENTS.md requires.

### 2. Time handling: instants inside, the dealership's zone only for calendar questions

- `BookAppointmentRequest.startTime` is an `OffsetDateTime`. Jackson rejects a value without an
  offset, and the existing handler reports it as `400` with an `errors` entry for `startTime`. The
  value is converted to an `Instant` immediately, and everything after that works on instants:
  overlap checks, the database (`timestamptz`), the fingerprint, and the "now" comparison.
- `BusinessDay.containing(start, zone, opens, closes)` takes the local date of `start` in the
  dealership's zone. `openingHours()` is
  `ZonedDateTime.of(date, opens, zone)` to `ZonedDateTime.of(date, closes, zone)`, so a daylight
  saving shift is handled by `java.time`. On 2026-03-29 in London, 08:00-18:00 local is
  07:00Z-17:00Z.
- The grid is checked on local clock time: minutes from `opens` to the local time of `start` must be
  a multiple of `slotStep`, with zero seconds and nanoseconds. A grid based on epoch instants would
  be wrong in zones with an offset that is not a whole hour (`+05:45`, `+05:30`).
- Rule order inside `StartTimeRule`: `IN_PAST` (`start <= now`), then `OUTSIDE_OPENING_HOURS`
  (`wanted` not inside `openingHours()`), then `NOT_ON_GRID`. A start at 07:45 for an 08:00
  opening is reported as outside opening hours, which tells the client more than "off grid".
- Responses convert back with `instant.atZone(zone).toOffsetDateTime()` and add `timeZone`. The
  JVM's default zone is never used. The test suite is run once with `-Duser.timezone=Asia/Kathmandu`
  (task 9.2) to prove it.
- Because an appointment must fit inside one day's opening hours, it never crosses local midnight.
  That makes "the appointments of a local day" the same as "`start_at` within
  `[dayStart, nextDayStart)`", a range the existing partial index serves.

### 3. The booking flow

```
POST /api/v1/appointments   [Idempotency-Key]
 │
 ├─ controller: @Valid body; header @Pattern([A-Za-z0-9._:-]{1,100}) ── 400 VALIDATION_FAILED
 │
 └─ BookingService.book()                       (no transaction of its own)
      ① key = header ?: UUID; fp = RequestFingerprint.of(request)
      ② read-only tx: find by key
            found, same fp  → 201 replay (current state)
            found, other fp → 422 IDEMPOTENCY_KEY_REUSED
      ③ same read-only tx: validate
            404 DEALERSHIP / CUSTOMER / VEHICLE / SERVICE_TYPE _NOT_FOUND
            422 VEHICLE_NOT_OWNED_BY_CUSTOMER
            422 START_TIME_IN_PAST / OUTSIDE_OPENING_HOURS / START_TIME_NOT_ON_GRID
            → BookingContext(dealership, zone, day, wanted range, serviceType, ...)
      ④ for attempt in 1..maxBookingAttempts:
            outcome = BookingAttempt.run(context)     (new transaction, decision 4)
            CONFIRMED / REPLAYED / errors  → respond
            LOST_RACE                      → conflicts++, next attempt
      ⑤ attempts exhausted → 409 SLOT_CONTENTION
```

- **Why ② comes before ③.** A client whose first request succeeded but timed out may retry after
  the start time has passed. If the rules ran first, the retry would get `START_TIME_IN_PAST` even
  though the booking exists.
- **Why `book()` has no transaction.** PostgreSQL aborts the whole transaction after a constraint
  violation, so a retry is only possible in a new one. Each attempt runs in its own
  `TransactionTemplate`.
- Validation runs outside the lock (decision 4), so the time a request spends holding the lock stays
  short.

### 4. One attempt: queue per dealership and local day, then allocate

Each attempt is one `READ COMMITTED` transaction:

```
SELECT set_config('lock_timeout', :ms, true);                         -- like SET LOCAL
SELECT pg_advisory_xact_lock(hashtextextended('booking:' || :dealershipId || ':' || :localDate, 0));
  1. find by key                → REPLAYED / KEY_REUSED   (a duplicate may have committed while we waited)
  2. vehicle overlap? (native: vehicle_id = :v AND status = 'CONFIRMED' AND period && tstzrange(:s, :e, '[)'))
                                → VEHICLE_ALREADY_BOOKED
  3. active bays of the dealership, ordered by id
     active technicians of the dealership with an active skill for the service type, ordered by id
     CONFIRMED appointments of the dealership with start_at in [dayStart, nextDayStart)
  4. Allocator.allocate(...)    → empty → NO_AVAILABILITY
  5. persist Appointment(id = random UUID, CONFIRMED, key, fp, created/updated = clock) and flush
COMMIT                                                                -- releases the lock
```

What the lock adds:
- **Bookings of one dealership-day run one after another.** Each request reads only after the
  previous one has committed. It never allocates a pair that is about to be taken, so it never
  fails because of a request that is merely running at the same time. Deterministic allocation
  (lowest bay, lightest technician) would otherwise make every concurrent request pick the same
  pair: one winner per round, and `SLOT_CONTENTION` while bays are still free. When capacity runs
  out, the next request in the queue sees that and gets `NO_AVAILABILITY` at once.
- **Granularity.** Bays and technicians belong to one dealership, and an appointment never crosses
  local midnight. So two bookings that can collide on a bay or a technician always share a
  dealership-day key. Other dealerships and other days run in parallel.
- **Throughput.** The lock is held only for a few indexed reads and one insert: a few
  milliseconds, so roughly 100+ bookings per second per dealership-day. A real dealership has a few
  bays × about 20 slots per day.
- **Many instances.** The lock lives in PostgreSQL, so it also serialises requests across several
  application instances. It is transaction-scoped and released by `COMMIT`/`ROLLBACK`, so it cannot
  leak, including through Supabase's session pooler.
- **Hash collisions.** `hashtextextended` may map two dealership-days to the same key. They are
  then serialised needlessly; correctness is unaffected.
- **Bounded waiting.** `lock_timeout` (`scheduler.booking-lock-timeout`, default 3 s; well below
  Hikari's 30 s connection timeout) ends the wait with SQLSTATE `55P03`, answered as `409
  SLOT_CONTENTION` without retrying, because waiting again would most likely time out again. The
  same timeout also bounds an insert waiting on another transaction's uncommitted conflicting row.
- **Step 1 repeats the key lookup inside the lock.** Two concurrent duplicates (same key, same
  body) both miss the lookup in ② and queue on the same lock. The second then finds the first's row
  and replays it. Without step 1, step 2 would see the duplicate's appointment and wrongly answer
  `VEHICLE_ALREADY_BOOKED`.
- The vehicle check uses a native query on `period &&`, so the GiST index of
  `appointment_no_vehicle_overlap` serves it. It covers every dealership, because a vehicle at
  another dealership is not under this lock.
- The JDBC calls (`set_config`, the lock, native queries) run through `JdbcClient` on the
  transaction's connection: `JpaTransactionManager` exposes it to `DataSourceUtils`.
- `Appointment` has an application-assigned UUID and a `Long version`. A `null` version tells
  Spring Data the entity is new, so `save` persists it instead of merging it (which would issue a
  `SELECT` first).

Layers of protection:

| Layer | Role | Failure if missing |
| --- | --- | --- |
| Advisory lock per dealership-day | fairness and throughput: requests do not fail because of each other | spurious `SLOT_CONTENTION` under bursts |
| Exclusion constraints (V1) | **the guarantee**: no overlapping confirmed rows, whatever writes them | double bookings |
| Retry on fresh data (max 5) | recovers when a writer bypassed the lock (direct SQL, a future endpoint) | a `500` or `409` where a free pair existed |

Alternatives considered:
- **Retry only (no lock).** Correct, but deterministic allocation makes bursts collide on the same
  pair. A test with 10 free pairs and 20 requests ends with many `SLOT_CONTENTION` responses.
- **Random choice among free pairs on retry.** Spreads collisions but breaks the workload-balancing
  rule, makes allocation tests non-deterministic, and still collides by chance.
- **`SELECT ... FOR UPDATE` on the dealership row.** Same effect at coarser granularity (all days of
  a dealership), and it would also block future writes to the dealership row. The advisory key
  costs nothing to create and needs no row.
- **`SERIALIZABLE` isolation.** Turns races into serialization failures that still need retries,
  so the burst problem stays.

### 5. Classifying database failures

`BookingFailures` walks the cause chain to the `PSQLException` and reads `getSQLState()` and
`getServerErrorMessage().getConstraint()`, the same way `SqlAssertions` does in the tests. It never
parses messages. Spring's translated exception types (`DataIntegrityViolationException`,
`CannotAcquireLockException`, ...) are not relied on.

| SQLSTATE / constraint | Outcome |
| --- | --- |
| `23505` `appointment_idempotency_key_unique` | look the key up in a new transaction: same fp → replay; other fp → `IDEMPOTENCY_KEY_REUSED`; not found → `LOST_RACE` |
| `23P01` `appointment_no_vehicle_overlap` | `VEHICLE_ALREADY_BOOKED` (no retry: another dealership holds the vehicle) |
| `23P01` `appointment_no_bay_overlap` / `appointment_no_technician_overlap` | `LOST_RACE` → next attempt on fresh data |
| `55P03` | `SLOT_CONTENTION` |
| `40P01` deadlock detected | `LOST_RACE` → next attempt (added during apply, see below) |
| anything else | rethrown → `500 INTERNAL_ERROR` |

Found while implementing: two concurrent bookings of one vehicle at two dealerships do not share a
lock. Each inserts its GiST index entry and then waits for the other's exclusion check, so
PostgreSQL's deadlock detector aborts one after `deadlock_timeout` (1 s). It surfaced as a `500` in
`BookingConcurrencyIntegrationTest`. The victim is a lost race: its retry sees the survivor's row
and answers `VEHICLE_ALREADY_BOOKED`. For this to be reported as a deadlock and not as a lock
timeout, `scheduler.booking-lock-timeout` must stay above `deadlock_timeout`. Reading
`PSQLException` needs the PostgreSQL driver at compile scope; it was `runtime` before.

A key lookup also precedes the vehicle and bay cases. A duplicate request from a client that
bypassed the lock is replayed rather than reported as a conflict, which is the trap identified in
explore: classifying by constraint name alone would turn a duplicate into `VEHICLE_ALREADY_BOOKED`.

### 6. Idempotency key and fingerprint

- When the header is missing, the key is `UUID.randomUUID().toString()`. That matches the V1 format
  check, and V1 makes the column `NOT NULL`. Server keys never collide, so such requests are never
  deduplicated (spec: "Requests without a key are not deduplicated").
- The fingerprint is the SHA-256 hex of
  `v1|dealershipId|customerId|vehicleId|serviceTypeId|startInstant`, where `startInstant` is
  `Instant.toString()` (UTC). Offset notation, JSON field order and whitespace therefore do not
  matter. The `v1|` prefix allows the definition to change later without silently matching old
  rows.
- A replay returns the stored appointment as it is now (possibly `CANCELLED`), with `201`,
  `Location` and `Idempotent-Replayed: true`.

### 7. Reading and cancelling

- `GET /{id}`: `@PathVariable UUID id`; a malformed UUID is a type mismatch, so `400` with an
  `errors` entry for `id`. One JPQL query joins appointment, dealership, bay and technician by id
  (entity joins on plain id values, no associations) and returns the view: zone, bay name,
  technician name.
- `POST /{id}/cancel` is one conditional update:

  ```sql
  UPDATE appointment SET status = 'CANCELLED', updated_at = :now, version = version + 1
  WHERE id = :id AND status = 'CONFIRMED' AND start_at > :now
  ```

  One row updated means cancelled. Zero rows means re-read the appointment: missing → `404`;
  `CANCELLED` → `200` unchanged; `CONFIRMED` (so already started) → `409
  APPOINTMENT_ALREADY_STARTED`.
  - Concurrent cancellations: the second `UPDATE` waits for the first's row lock, re-evaluates its
    `WHERE` on the committed row (`READ COMMITTED`), matches nothing, re-reads `CANCELLED` and
    answers `200`. No optimistic-lock exception can surface as a `500`.
  - Cancellation does not take the booking lock: it only frees resources. A booking running at the
    same moment may still see the appointment as confirmed and decline that pair. That is
    conservative, never a double booking.

### 8. Clock

A `Clock` bean (UTC) is the only source of "now", used for the start-time rule, cancellation, and
`created_at`/`updated_at`. `ProbeConfiguration` adds a `@Primary` mutable test clock, which
`DatabaseIntegrationTest` resets before each test to `2026-11-02T00:00:00Z`. Tests can therefore
book fixed dates, and can move time forward to test "after the start time", without a second Spring
context.

### 9. Metrics and log events

Metrics (Micrometer, exported by the existing Prometheus registry):
- `scheduler.bookings` (Prometheus `scheduler_bookings_total`), tag `outcome`. Incremented once per
  call of `BookingService.book()`:
  - `confirmed`, `replayed`, `no_availability`, `contention`;
  - `rejected` for any other `ApiException`;
  - `error` for any other exception (answered `500 INTERNAL_ERROR`). Added during archive review,
    when the user chose to make "every validated request counts exactly once" hold for unexpected
    failures too, instead of narrowing the spec.

  `400` responses never reach the service, so they are not counted, as the observability spec
  requires.
- `scheduler.booking.conflicts` (`scheduler_booking_conflicts_total`): one per `LOST_RACE`. With the
  lock in place it should stay at 0. A rising value means something writes appointments without
  taking the lock.
- `management.metrics.distribution.percentiles-histogram.http.server.requests: true`, so
  `http_server_requests_seconds` exports buckets, as the observability spec already requires.

Log events (all carry `requestId`; customer data appears only as ids):
- INFO `Appointment {id} confirmed: dealership {d}, bay {b}, technician {t}, {start}/{end}, attempt {n}`
- INFO `Booking replayed: appointment {id}`
- WARN `Booking attempt {n} lost a race on {constraint}; retrying`. This is WARN because the lock
  should make it impossible.
- INFO `Appointment {id} cancelled`
- Existing: one access log line per request, and one WARN problem line per 4xx (including
  `NO_AVAILABILITY` and `SLOT_CONTENTION`). See Open Questions.

### 10. Error codes

Thirteen constants are added to `ErrorCode`, each with a detail template that contains only values
the client sent:
- `404`: `SERVICE_TYPE_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `VEHICLE_NOT_FOUND`,
  `APPOINTMENT_NOT_FOUND`;
- `422`: `VEHICLE_NOT_OWNED_BY_CUSTOMER`, `START_TIME_IN_PAST`, `OUTSIDE_OPENING_HOURS`,
  `START_TIME_NOT_ON_GRID`, `IDEMPOTENCY_KEY_REUSED`;
- `409`: `NO_AVAILABILITY`, `SLOT_CONTENTION`, `VEHICLE_ALREADY_BOOKED`,
  `APPOINTMENT_ALREADY_STARTED`.

`409` means the request is valid but the current state of resources prevents it. `422` means the
request itself breaks a rule. `ErrorCodeTest` already enforces that each constant appears in a spec.

### 11. Test approach

- **Pure unit tests** for `scheduling`:
  - overlap at the half-open boundary;
  - the grid at `+05:45` and on a DST day;
  - rule order;
  - allocation: lowest free bay, lightest technician, ties by id, cancelled work excluded (it is
    never passed in), no free pair.
- **HTTP integration tests** (`AppointmentBookingIntegrationTest`,
  `AppointmentCancellationIntegrationTest`, `BookingConcurrencyIntegrationTest`) on fixtures, one
  test per spec scenario.
- **Concurrency tests** fire requests from a thread pool through a start latch:
  - 20 requests against 2 free pairs;
  - 4 requests against 4 free pairs;
  - different offsets;
  - same-key duplicates;
  - one vehicle at two dealerships;
  - concurrent cancellations.
- **The lost-race test is deterministic.** A test connection inserts a conflicting row for bay 1
  without committing. The booking thread allocates bay 1 and blocks on the exclusion check. The test
  polls `pg_stat_activity` until a backend waits on a `Lock`, then commits. The booking must answer
  `201` on bay 2, and `scheduler_booking_conflicts_total` must increase by one.
- **The lock-timeout test** holds the same advisory key from a test connection longer than the
  timeout and expects `409 SLOT_CONTENTION`. The base test class sets
  `scheduler.booking-lock-timeout=2s`, to stay fast without becoming flaky.

## Risks / Trade-offs

- [The lock serialises bookings of one dealership-day] → It is held for milliseconds. The capacity
  of a real dealership-day is orders of magnitude below that throughput. Other dealerships and days
  are unaffected.
- [Waiting requests hold a pooled connection (pool of 5)] → Waits are short and bounded by
  `lock_timeout` (3 s, below Hikari's 30 s). A burst queues in Hikari and then on the lock without
  errors. A dedicated booking pool or a bulkhead is the next step if catalog reads must never wait
  behind bookings.
- [The retry path is rarely exercised in production] → A dedicated deterministic test (decision
  11), and the conflicts metric makes any occurrence visible.
- [An insert waiting on a direct-SQL writer counts towards `lock_timeout`] → That ends in
  `SLOT_CONTENTION` after 3 s, which is acceptable for a writer outside the application.
- [An invalid IANA zone in a dealership row makes `ZoneId.of` fail with a `500`] → Dealership rows
  come only from migrations and demo data. Validating zones on write belongs to a future catalog
  write API.
- [Clock skew between instances shifts the "in the past" and "already started" edges] → Hosts run
  NTP. Rules compare at second granularity against slot-aligned times.
- [Jackson writes a zero offset as `Z` rather than `+00:00`] → Both are ISO-8601. Tests compare
  parsed instants and offsets, not strings, for London winter dates.
- [A future change of the fingerprint definition would stop replays of old keys] → The `v1|`
  prefix makes such a change explicit.

## Migration Plan

There is no schema change: V1 already has every column and constraint. Deploying means starting
the new build; rolling back means redeploying the previous build. Appointments created by this
version remain valid rows for any later version. New configuration properties all have defaults.

## Open Questions

- Should `GlobalExceptionHandler` log expected booking outcomes (`NO_AVAILABILITY`,
  `SLOT_CONTENTION`, `VEHICLE_ALREADY_BOOKED`) at INFO instead of WARN? A 20-request burst writes
  18 WARN lines that are normal business results. The access log and `scheduler_bookings_total`
  already record them. This is left as is for now (the proposal lists it as a non-goal); deciding it
  changes an existing test from `add-api-foundation`.
