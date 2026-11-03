# System Design

Unified Service Scheduler, Keyloop technical assessment, Scenario A. This document grows with each
OpenSpec change; the behaviour itself is specified in `openspec/specs/`. The availability search
section arrives with the change that builds it.

## Data model

Created by `src/main/resources/db/migration/V1__scheduler_schema.sql` (OpenSpec change
`add-scheduler-database`).

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

| Table | Holds |
| --- | --- |
| `dealership` | name, IANA time zone, daily opening and closing time |
| `service_type` | unique code, name, duration in minutes |
| `service_bay` | a bay in one dealership, active flag |
| `technician` | a technician in one dealership, active flag |
| `technician_skill` | a technician's qualification for a service type, active flag (retire instead of delete) |
| `customer` | name, unique email, optional phone |
| `vehicle` | unique VIN (ISO 3779 format), make, model, year, current owner |
| `appointment` | dealership, customer, vehicle, service type, bay, technician, `start_at`/`end_at`, generated half-open `period`, status `CONFIRMED`/`CANCELLED`, idempotency key, request fingerprint, timestamps, `version` |

Times are stored as `timestamptz` instants. `period` is `tstzrange(start_at, end_at, '[)')`, so an
appointment may start exactly when another ends, and inputs given in different offsets are compared
as instants. Appointment ids are random UUIDs because they are handed to customers; reference data
uses `bigint` identities.

### Who enforces what

The database is the final arbiter for every rule it can express. Application checks run first only
to fail fast with a precise error; they are never the only guard.

| Rule | Enforced by | Failure |
| --- | --- | --- |
| A bay is never double-booked | `appointment_no_bay_overlap` (`EXCLUDE USING gist`, confirmed rows only) | `23P01` |
| A technician is never double-booked | `appointment_no_technician_overlap` | `23P01` |
| A vehicle has one booking at a time | `appointment_no_vehicle_overlap` | `23P01` |
| Cancelling frees the bay, technician and vehicle | partial predicate `WHERE status = 'CONFIRMED'` on the three constraints | - |
| The technician is qualified for the service type | `appointment_qualification_fk` → `technician_skill` | `23503` |
| Bay and technician belong to the appointment's dealership | `appointment_bay_fk`, `appointment_technician_fk` (composite keys) | `23503` |
| A used qualification is not deleted | `appointment_qualification_fk` | `23503` |
| End after start; known status | `appointment_time_order`, `appointment_status_valid` | `23514` |
| An idempotency key books at most once | `appointment_idempotency_key_unique` | `23505` |
| Key and fingerprint format | `appointment_idempotency_key_format`, `appointment_request_fingerprint_format` | `23514` |
| VIN format and uniqueness; positive durations; closing after opening | `vehicle_vin_format`, `vehicle_vin_unique`, `service_type_duration_positive`, `dealership_hours_order` | `23514` / `23505` |
| Bay and technician are active; technician's skill is active; slot inside opening hours | application (booking) | `409` / `422` |
| The vehicle belongs to the customer | application (booking); deliberately not a foreign key because vehicles change owners | `422` |

The three exclusion constraint names are a contract with the booking flow: a bay or technician
conflict means "try another allocation", a vehicle conflict means `VEHICLE_ALREADY_BOOKED`.

### Correctness under concurrent bookings

Checking availability and then inserting is a race: two requests can both see the same bay free.
Exclusion and unique constraints close it, because PostgreSQL checks them against the index at
insert time, including rows of transactions that have not committed yet. The second insert waits
for the first transaction's outcome:

```
 T1: INSERT bay 1, 10:00-11:00 ─────────────── COMMIT
 T2:        INSERT bay 1, 10:30-11:30 ── waits for T1 ──▶ T1 committed → 23P01, T2 rolls back
                                                         (T1 rolled back → T2 succeeds)
```

This holds at the default `READ COMMITTED` isolation level, without `SERIALIZABLE`, advisory locks
or `SELECT ... FOR UPDATE`. The same mechanism makes two requests with the same idempotency key
commit exactly once (`23505` for the loser). `ConcurrentInsertIntegrationTest` reproduces both
races with two connections on two threads. The booking flow puts a queue in front of these
constraints so that concurrent requests do not fail because of each other, and retries a lost race
in a new transaction (see [Booking](#booking)).

### Where the data lives

- Every application table, including Flyway's history, is in schema `scheduler`; nothing is created
  in `public`. Supabase exposes `public` to anonymous callers through its Data API (PostgREST); this
  backend uses only JDBC, so keeping out of `public` removes that exposure.
- V1 also revokes all privileges of Supabase's `anon` and `authenticated` roles on `scheduler`,
  including default privileges for future tables, in a block that does nothing where those roles do
  not exist (plain PostgreSQL, Testcontainers).
- `btree_gist` (needed for `=` on `bigint` inside a GiST exclusion constraint) is installed in schema
  `extensions`, which Supabase provides and V1 creates where it is missing.
- Schema changes are new versioned migrations (`V2__...`); an applied migration is never edited.
  Hibernate only validates (`ddl-auto: validate`).

### Demo data

`src/main/resources/db/demo/R__demo_data.sql` is a Flyway repeatable migration in its own location.
It runs after the versioned migrations and inserts two dealerships (`Europe/London`,
`Asia/Ho_Chi_Minh`), four service types, five bays, five technicians (one inactive) with their
qualifications, two customers and three vehicles, but only when `dealership` is empty, so restarts
and edits to the script never duplicate rows. The application loads `db/migration` and `db/demo`;
tests load `db/migration` only and run on a throwaway PostgreSQL 17 container, never on Supabase.

A repeatable migration rather than `V100__demo_data.sql`: once V100 is applied, Flyway treats any
later `V2__...` as an ignored lower version and fails validation.

## API

Built by OpenSpec changes `add-api-foundation`, `add-appointment-booking` and `add-supabase-auth`.
Swagger UI: `/swagger-ui.html`.

| Endpoint | Token | Returns |
| --- | --- | --- |
| `GET /api/v1/service-types` | public | `{"items": [...]}` of `id`, `code`, `name`, `durationMinutes` |
| `GET /api/v1/dealerships` | public | `{"items": [...]}` of `id`, `name`, `timeZone`, `opens`, `closes` (`HH:mm`, local) |
| `GET /api/v1/dealerships/{id}` | public | the dealership plus `serviceBays` and `technicians` (with `qualifiedServiceTypeIds`) |
| `POST /api/v1/appointments` | required | `201` + `Location`: the booked appointment; optional `Idempotency-Key` header |
| `GET /api/v1/appointments/{id}` | required | the appointment in its current state |
| `POST /api/v1/appointments/{id}/cancel` | required | `200`: the cancelled appointment (idempotent) |

"Required" means `Authorization: Bearer <Supabase access token>`, and what the caller may do depends
on its role (see Access control).

An appointment carries `id`, `status`, the four request ids, `serviceBay` and `technician`
(`{id, name}`), and `startTime`/`endTime` with the dealership's offset plus its `timeZone`:

```json
{
  "id": "9c99d593-4b58-4cdf-9e59-74a8b4e7b647",
  "status": "CONFIRMED",
  "dealershipId": 2, "customerId": 2, "vehicleId": 3, "serviceTypeId": 1,
  "serviceBay": { "id": 4, "name": "Bay B" },
  "technician": { "id": 4, "name": "Tran Thi Binh" },
  "startTime": "2026-11-03T10:00:00+07:00",
  "endTime": "2026-11-03T10:45:00+07:00",
  "timeZone": "Asia/Ho_Chi_Minh"
}
```

### Response conventions

- A successful body is the resource itself. There is no `{success, data}` envelope: the HTTP status
  carries the outcome, so `http_server_requests_seconds`, proxies and retries see real error statuses.
- Collections are `{"items": [...]}`, ordered by id, so pagination can be added without breaking
  clients.
- Every response carries `X-Request-Id`: the caller's value when it matches `[A-Za-z0-9._-]{1,64}`,
  otherwise a new UUID.

### One error pipeline

Every failure ends as the same RFC 9457 problem, whichever layer detects it:

```
 request ─▶ RequestIdFilter ─▶ other filters ─▶ DispatcherServlet ─▶ controller ─▶ service
              │ id → header, MDC,      │                  │                           │
              │ request attribute      │                  │ Spring MVC exceptions     │ ApiException(ErrorCode)
              │                        │                  ▼                           ▼
              │                        │        GlobalExceptionHandler (@RestControllerAdvice)
              │                        │ exception                 │
              │                        ▼ escapes                   │
              │              container error dispatch              │
              └─(runs again, ─▶ ProblemErrorController (/error)    │
                 same id)                  │                       │
                                           ▼                       ▼
                         Problems.create: type, title, status, detail, instance,
                                          code, requestId (+ errors for VALIDATION_FAILED)
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Dealership 999 does not exist.",
  "instance": "/api/v1/dealerships/999",
  "code": "DEALERSHIP_NOT_FOUND",
  "requestId": "7c2f9d0a-1b3e-4c5f-8a6b-9d0e1f2a3b4c"
}
```

All codes are constants of the `ErrorCode` enum, each with its status and default detail. A unit test
fails when a constant is missing from the specs under `openspec/`.

| Code | Status | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | invalid body field, query parameter, path variable or header, or unreadable JSON; `errors` names each input |
| `NOT_FOUND` | 404 | no endpoint for the path |
| `METHOD_NOT_ALLOWED` | 405 | path exists, method does not (`Allow` lists the methods) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | request body content type not accepted |
| `REQUEST_REJECTED` | other 4xx | fallback, keeps the original status |
| `INTERNAL_ERROR` | 500 (other 5xx) | anything unexpected; generic detail, stack trace only in the log |
| `UNAUTHENTICATED` | 401 | no token where one is needed, or a token that is malformed, expired, signed by an unknown key, or for another issuer or audience; with `WWW-Authenticate: Bearer` |
| `FORBIDDEN` | 403 | a valid token whose role does not allow the request (see Access control) |
| `DEALERSHIP_NOT_FOUND` | 404 | unknown dealership id |
| `SERVICE_TYPE_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `VEHICLE_NOT_FOUND`, `APPOINTMENT_NOT_FOUND` | 404 | unknown reference |
| `VEHICLE_NOT_OWNED_BY_CUSTOMER` | 422 | the vehicle belongs to another customer |
| `START_TIME_IN_PAST` | 422 | start not after now |
| `OUTSIDE_OPENING_HOURS` | 422 | the service would start before opening or end after closing (local time) |
| `START_TIME_NOT_ON_GRID` | 422 | start not a whole number of `scheduler.slot-step`s after opening (local time) |
| `IDEMPOTENCY_KEY_REUSED` | 422 | the key was used for different booking parameters |
| `NO_AVAILABILITY` | 409 | no active bay and qualified technician both free for the whole period |
| `VEHICLE_ALREADY_BOOKED` | 409 | the vehicle has an overlapping confirmed appointment, at any dealership |
| `SLOT_CONTENTION` | 409 | waited longer than `scheduler.booking-lock-timeout`, or lost every attempt |
| `APPOINTMENT_ALREADY_STARTED` | 409 | cancelling at or after the start time |

`422` means the request itself breaks a rule; `409` means the request is valid but the current state
of resources prevents it. The availability search adds its codes with the change that builds it.

### Request id and logs

- Every console line carries the id as `[<requestId>]` (`logging.pattern.correlation`), and on an
  authenticated request also the caller's Supabase user id: `[<requestId> <userId>]`. Never an email
  or a name. With `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, the ECS JSON lines carry them as
  `requestId` and `userId` fields. The access log line carries the request id only.
- One access log line per `/api/**` request: `GET /api/v1/dealerships -> 200 (12 ms)`. Health
  probes are not logged.
- A 4xx problem is logged at WARN without a stack trace. A 5xx problem is logged at ERROR with the
  exception, which is the only place its message appears.

## Booking

Built by OpenSpec change `add-appointment-booking` (Scenario A core requirements 1-3).

### Components

```
 AppointmentController ── POST /appointments ──▶ BookingService ── each attempt ──▶ BookingAttempt
        │                                          │  replay check, validation          │ lock, allocate, insert
        │ GET /{id}, POST /{id}/cancel             │  (read-only transaction)           │ (own transaction)
        ▼                                          ▼                                    ▼
 AppointmentService                         scheduling (pure domain)          BookingFailures
   read view, conditional cancel            TimeRange, BusinessDay,           SQLSTATE + constraint
                                            StartTimeRule, Allocator          → outcome
```

`scheduling` has no Spring or JPA dependency and is unit-tested on its own; the availability search
will reuse it. Services translate its results (`StartTimeViolation`, an empty `Allocation`) into
`ApiException`s.

### Flow

```
POST /api/v1/appointments   Authorization: Bearer <token>   [Idempotency-Key]
 ├─ security chain: valid token ─────────────────────────────────────────── 401 UNAUTHENTICATED
 ├─ controller: @Valid body, header pattern ─────────────────────────────── 400 VALIDATION_FAILED
 └─ BookingService (no transaction of its own)
     ⓪ AccessPolicy.mayBook(caller, dealership, customer)? ──────────────── 403 FORBIDDEN
     ① key = header, or a random UUID; fingerprint = SHA-256("v1|ids|start instant")
     ② key known? same fingerprint → 201 replay (current state); other → 422 IDEMPOTENCY_KEY_REUSED
     ③ read-only tx: 404 dealership/customer/vehicle/service type, 422 ownership,
                     422 in the past / outside opening hours / off the grid
     ④ up to scheduler.max-booking-attempts (5) times, each in a NEW transaction:
          lock_timeout; pg_advisory_xact_lock(dealership, local day)       ← queue
          key known now? → replay          (a duplicate committed while this one waited)
          vehicle overlaps at any dealership? → 409 VEHICLE_ALREADY_BOOKED
          active bays, active qualified technicians, that day's confirmed appointments
          Allocator → none → 409 NO_AVAILABILITY
          INSERT + flush → COMMIT (releases the lock) → 201
          database rejected it → BookingFailures (table below)
     ⑤ every attempt lost → 409 SLOT_CONTENTION
```

The permission check (⓪) runs before the replay (②). A customer can put any `customerId` in the
body, so a replay first would hand another customer's appointment to anyone who knows its key and
request.

The replay check (②) runs before the rules (③): a client whose first request succeeded but timed
out may retry after the start time, and must get its appointment back rather than
`START_TIME_IN_PAST`. `BookingService` has no transaction because PostgreSQL aborts a transaction
after a constraint violation; a retry is only possible in a new one.

### Time handling

Times are instants everywhere between the API edges. The request's `startTime` must carry an offset
and is converted to an `Instant` at once; responses convert back to the dealership's zone. The
dealership's zone is used only for calendar questions, in `BusinessDay`:

- opening hours: `ZonedDateTime.of(date, opens, zone)` to `ZonedDateTime.of(date, closes, zone)`,
  so daylight saving time is applied (London on 2026-03-29: 08:00-18:00 local is 07:00Z-17:00Z);
- the slot grid: minutes from `opens` to the local clock time of the start must be a multiple of
  `scheduler.slot-step`. A client at `+05:45` booking 10:00 in Saigon (`08:45+05:45`) is on the grid;
  an epoch-based grid would not be;
- the local day: from local midnight to the next. An appointment fits inside one day's opening hours,
  so "that day's appointments" is a `start_at` range served by `appointment_dealership_start_idx`.

The JVM's default zone is never read; the suite also passes with `-Duser.timezone=Asia/Kathmandu`.

### Three layers against double booking

| Layer | Role | Without it |
| --- | --- | --- |
| `pg_advisory_xact_lock(hash('booking:<dealership>:<local date>'))` | queue: bookings that could compete for a bay or technician run one after another, each seeing the previous commits | allocation is deterministic, so concurrent requests pick the same pair: one winner per round, `SLOT_CONTENTION` while bays are still free |
| Exclusion constraints (V1) | **the guarantee**: no overlapping confirmed rows, whatever writes them | double bookings |
| Retry on fresh data, up to 5 attempts | recovers when a writer bypassed the lock, or two dealerships deadlock on one vehicle | a `500` or `409` although a free pair existed |

Bays and technicians belong to one dealership and an appointment never crosses local midnight, so
two bookings that can collide on a bay or technician always share the lock key; other dealerships
and days run in parallel. The lock is held for a few indexed reads and one insert (a 20-request
burst completes in under a second in the tests), against a real capacity of a few bays times about
20 slots per day. It lives in PostgreSQL, so it also serialises several application instances, and
it is transaction-scoped, so `COMMIT`/`ROLLBACK` release it, including through Supabase's session
pooler. Waiting is bounded by `lock_timeout` (`scheduler.booking-lock-timeout`, 3 s), which must
stay above PostgreSQL's `deadlock_timeout` (1 s) so that a deadlock is reported as such.

`BookingFailures` reads the SQLSTATE and constraint name from PostgreSQL's own error, never the
message:

| Database error | Outcome |
| --- | --- |
| `55P03` lock not available | `409 SLOT_CONTENTION`, no retry |
| `40P01` deadlock detected | lost race → next attempt |
| `23505` / `23P01`, idempotency key now known | replay (same fingerprint) or `422 IDEMPOTENCY_KEY_REUSED` |
| `23P01` `appointment_no_vehicle_overlap` | `409 VEHICLE_ALREADY_BOOKED` |
| `23P01` bay or technician overlap; `23505` key whose owner rolled back | lost race → next attempt |
| anything else | `500 INTERNAL_ERROR` |

The key lookup comes before the constraint name: a duplicate request that collided with its own
original on the vehicle is a replay, not `VEHICLE_ALREADY_BOOKED`.

A deadlock is possible because two inserts that do not share a lock (one vehicle booked at two
dealerships at the same instant) each write their GiST index entry and then wait for the other's
exclusion check. PostgreSQL aborts one; its retry finds the survivor's row. The concurrency test
reproduces this intermittently, and `BookingFailuresTest` covers the classification deterministically.

### Cancellation

One conditional statement decides:
`UPDATE ... SET status = 'CANCELLED' WHERE id = ? AND status = 'CONFIRMED' AND start_at > now`.
A concurrent second cancellation waits for the row lock, re-checks its condition on the committed row
under `READ COMMITTED`, changes nothing and answers `200` with the cancelled appointment. Zero rows
changed on a still confirmed appointment means it has started: `409 APPOINTMENT_ALREADY_STARTED`.
Cancelling does not take the booking lock; a booking running at that moment may still decline the
pair, which is conservative, never a double booking. The caller's right to see the appointment is
checked before that statement (see Access control).

### Metrics and log events

- `scheduler_bookings_total{outcome}`: one per booking request that passed request validation:
  `confirmed`, `replayed`, `no_availability`, `contention`, `rejected` (any other booking error
  code), `error` (an unexpected `500`).
  Requests rejected with `400` are visible only in `http_server_requests_seconds`.
- `scheduler_booking_conflicts_total`: lost races. With the lock in place it stays near 0; a rising
  value means something writes appointments without taking the lock.
- `http_server_requests_seconds` exports histogram buckets for latency percentiles.
- INFO `Appointment <id> confirmed: dealership, bay, technician, start/end, attempt`; INFO
  `Booking replayed`; INFO `Appointment <id> cancelled`; WARN `Booking attempt <n> lost a race on
  <constraint>`. Customers appear only as ids.

## Access control

Built by OpenSpec change `add-supabase-auth`. People log in with Supabase Auth, which issues the
access tokens. The backend has no login endpoint and never sees a password: it is an OAuth2 resource
server that verifies each token locally.

### Flow

```
 client ── e-mail + password ──▶ Supabase Auth /auth/v1/token ──▶ access token (ES256 JWT, ~1 h)
 client ── Authorization: Bearer <token> ──▶ backend
   RequestIdFilter (X-Request-Id, MDC)
   └─ security chain, stateless (no session, no cookie, no CSRF)
       ├─ BearerTokenAuthenticationFilter, if a token is sent:
       │    signature (project JWKS, cached), exp/nbf, iss, aud ──────────── 401 UNAUTHENTICATED
       ├─ SupabaseJwtConverter: sub + app_metadata → AuthenticatedUser, ROLE_*
       ├─ UserIdLogFilter: userId → MDC
       └─ URL rules:
            catalog, Swagger UI, /v3/api-docs, /actuator/health/** ──────── public
            /actuator/** (prometheus, metrics, info) ─────────────────────── ADMIN, else 403 FORBIDDEN
            anything else, unknown paths included ────────────────────────── token, else 401
   AppointmentController (@AuthenticationPrincipal AuthenticatedUser)
   └─ BookingService / AppointmentService → AccessPolicy (pure, unit-tested)
        booking not allowed ─────────────────────────────────────────────── 403 FORBIDDEN
        appointment not visible ─────────────────────────────────────────── 404 APPOINTMENT_NOT_FOUND
```

The entry point and the access-denied handler only set the status (`sendError`). The container's
error dispatch then reaches `ProblemErrorController`, so `401` and `403` are the same problem+json as
every other error, with `requestId` and, on `401`, `WWW-Authenticate: Bearer`. A token that fails
verification is rejected even on a public path.

### Roles

The role and its link are in the token's `app_metadata`, which only the Supabase dashboard or the
Admin API can change. `user_metadata` is writable by the user and never read.

| `app_metadata.scheduler_role` | Needs | Books | Reads and cancels | Actuator |
| --- | --- | --- | --- | --- |
| `customer` | `customer_id` | only with that `customerId`, at any dealership | that customer's appointments | no |
| `advisor` | `dealership_id` | any customer, only at that dealership | that dealership's appointments | no |
| `admin` | - | everything | everything | yes |
| missing, unknown, or without the id it needs | - | `403` | `403` | `403` |

- Ids may be JSON numbers or numeric strings; role names are case-insensitive.
- An appointment the caller may not see answers `404 APPOINTMENT_NOT_FOUND`, exactly like an unknown
  id, so a response never confirms that someone else's appointment exists.
- Booking checks the caller first, before the idempotency replay and every booking rule (request
  validation, `400`, still comes first). A forbidden booking counts as
  `scheduler_bookings_total{outcome="rejected"}`.
- Cancelling checks visibility before the conditional update, so a forbidden cancel changes nothing.
  The dealership and customer of an appointment never change after insert, so no concurrent request
  can invalidate that check.

### Token verification

- `spring.security.oauth2.resourceserver.jwt` in `application.yml`: the JWKS URI and the issuer come
  from `SUPABASE_URL`; ES256 only (Spring would otherwise expect RS256); audience `authenticated`.
  Spring Boot builds the decoder and its validators; the application defines none of its own.
- No Supabase secret is in the backend. The public keys are fetched on the first token and cached;
  a token with an unknown `kid` triggers a new fetch, which handles key rotation. The application
  starts even when Supabase is unreachable; new tokens then answer `401` until the keys can be read.
- Expiry uses Spring's system clock with 60 s of skew, not the application `Clock`: tokens are
  real-time artefacts, booking rules use business time.
- A role change applies when the user's token is next refreshed (at most about an hour). Revocation
  before expiry is not supported.
- Tests never call Supabase. `TestTokens` signs tokens with a key pair generated at startup, and
  Boot's real decoder downloads that key's JWKS from memory, so the tests exercise the production
  validators (`TestSecurityConfiguration`).

### Getting a token

1. Put `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` and `SUPABASE_SECRET_KEY` in `.env` (template in
   `.env.example`). Only the demo script and cURL use the two keys; the backend needs the URL only.
2. Create the demo users (admin, an advisor of the Saigon dealership, customers Emma and Duc):

   ```bash
   DEMO_USER_PASSWORD='choose-one' python scripts/supabase-demo-users.py
   ```

3. Log in and call the API:

   ```bash
   TOKEN=$(curl -s "$SUPABASE_URL/auth/v1/token?grant_type=password" \
     -H "apikey: $SUPABASE_PUBLISHABLE_KEY" -H "Content-Type: application/json" \
     -d '{"email": "emma.wilson@example.com", "password": "choose-one"}' \
     | python -c "import json, sys; print(json.load(sys.stdin)['access_token'])")
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/appointments/<id>
   ```

   In Swagger UI, "Authorize" takes the same token.

## Code conventions

- DTOs are Java records; each response has a static `of()` mapper. Mappings convert time zones and
  combine several sources, which is shorter and type-checked as plain Java (no MapStruct).
- JPA entities use Lombok `@Getter` and nothing else from Lombok: no setters (state changes go
  through factories such as `Appointment.confirmed` and conditional updates), no generated
  `equals`/`hashCode`/`toString`, no builders (OpenSpec change `adopt-lombok-getters`).

## Assumptions

Ambiguities in the brief, resolved as follows:

- A technician is available when they have no overlapping confirmed appointment (no shifts or time
  off yet).
- Any active service bay can host any service type (no bay types).
- A dealership has the same opening hours every day and no holidays.
- Idempotency keys are global, not scoped per user, and live as long as their appointment. The
  permission check before the replay stops a caller from replaying someone else's booking.
- A technician whose qualification for a service type is inactive is not qualified for it.
  Inactive bays and technicians are still listed by the catalog, with `active: false`.
- Catalog lists are small and returned unpaginated; the `items` wrapper leaves room for pagination.
- Clients branch on an error's `code`, never on `title` or `detail`. Messages are English and may
  change.
- Start times lie on a grid of `scheduler.slot-step` (default 30 minutes), anchored at the
  dealership's opening time in its local time zone.
- An appointment fits inside one day's opening hours, so it never crosses local midnight.
- A start time equal to the current instant is already in the past; cancellation is allowed only
  strictly before the start time.
- `startTime` must carry an offset (`Z` or `±hh:mm`); a local date-time without one is ambiguous and
  rejected.
- Two requests carry "the same booking parameters" when dealership, customer, vehicle, service type
  and start instant are equal, whatever offset notation was used.
- Replaying the key of a cancelled appointment returns it as it is now (`CANCELLED`), not a new
  booking. A request without a key gets a server-generated one and is never deduplicated.
- Vehicle ownership is checked when booking; a later change of owner does not affect existing
  appointments. A vehicle cannot hold two overlapping appointments, even at different dealerships.
- A Supabase user is linked to a customer, or to a dealership for staff, by an administrator setting
  `app_metadata` (`scheduler_role`, `customer_id`, `dealership_id`). There is no self-service linking.
- An advisor may book for any customer at their own dealership, and sees all of that dealership's
  appointments.
- A valid token without a known role, or without the id its role needs, is authenticated but has no
  permission on appointments (`403`).
- Another user's appointment is reported as not found rather than forbidden.
- Catalog data (dealerships, bays, technician names, service types) is public.
- Every actuator endpoint except health is for admins only. A separate management port for
  Prometheus scraping is the production follow-up.
