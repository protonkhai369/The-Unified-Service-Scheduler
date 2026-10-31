# System Design

Unified Service Scheduler, Keyloop technical assessment, Scenario A. This document grows with each
OpenSpec change; the behaviour itself is specified in `openspec/specs/`. Sections still to come
(booking flow, availability search, metrics) arrive with the changes that build them.

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
| Bay and technician are active; technician's skill is active; slot inside opening hours | application (booking change) | `409` / `422` |
| The vehicle belongs to the customer | application (booking change); deliberately not a foreign key because vehicles change owners | `422` |

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
races with two connections on two threads. A booking that loses a bay/technician race retries
allocation in a new transaction (booking change).

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

Built by OpenSpec change `add-api-foundation`. Swagger UI: `/swagger-ui.html`.

| Endpoint | Returns |
| --- | --- |
| `GET /api/v1/service-types` | `{"items": [...]}` of `id`, `code`, `name`, `durationMinutes` |
| `GET /api/v1/dealerships` | `{"items": [...]}` of `id`, `name`, `timeZone`, `opens`, `closes` (`HH:mm`, local) |
| `GET /api/v1/dealerships/{id}` | the dealership plus `serviceBays` and `technicians` (with `qualifiedServiceTypeIds`) |

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
| `DEALERSHIP_NOT_FOUND` | 404 | unknown dealership id |

Booking and availability add their codes (`NO_AVAILABILITY`, `SLOT_CONTENTION`, ...) with the
changes that build them.

### Request id and logs

- Every console line carries the id as `[<requestId>]` (`logging.pattern.correlation`). With
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, the ECS JSON lines carry it as a `requestId` field.
- One access log line per `/api/**` request: `GET /api/v1/dealerships -> 200 (12 ms)`. Health
  probes are not logged.
- A 4xx problem is logged at WARN without a stack trace. A 5xx problem is logged at ERROR with the
  exception, which is the only place its message appears.

## Assumptions

Ambiguities in the brief, resolved as follows:

- A technician is available when they have no overlapping confirmed appointment (no shifts or time
  off yet).
- Any active service bay can host any service type (no bay types).
- A dealership has the same opening hours every day and no holidays.
- Idempotency keys are global (there is no client identity yet) and live as long as their
  appointment.
- A technician whose qualification for a service type is inactive is not qualified for it.
  Inactive bays and technicians are still listed by the catalog, with `active: false`.
- Catalog lists are small and returned unpaginated; the `items` wrapper leaves room for pagination.
- Clients branch on an error's `code`, never on `title` or `detail`. Messages are English and may
  change.
