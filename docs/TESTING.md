# Testing

How the Unified Service Scheduler is tested, and how to check it yourself: the automated suites,
a live environment with demo users and data, manual scenarios for the web client and the API, and
a live demo of slot contention and idempotency.

| Level | Command | Needs | What it shows |
| --- | --- | --- | --- |
| Backend unit and integration tests | `./mvnw test` | JDK 21+, Docker | Every specified scenario of the API, on a real PostgreSQL 17 |
| Frontend tests | `npm --prefix frontend test` | Node 20+ | Every scenario of the web client, against a mocked API |
| Static checks | `npm --prefix frontend run typecheck`, `npm --prefix frontend run build`, `openspec validate --all --strict` | Node, OpenSpec CLI | Types, the production bundle, the specs |
| Manual end-to-end | [Manual scenarios](#3-manual-scenarios-web-client) | A Supabase project, the demo users | The real stack: browser, Supabase Auth, backend, database |
| Live race demo | `python scripts/race-demo.py` | The backend running, the demo users | Parallel bookings against the real stack, drawn as a timeline |

## 1. Automated tests

### Backend

```bash
./mvnw test
./mvnw test -Dtest=BookingConcurrencyIntegrationTest     # one class
```

- The project targets Java 21. If your default JDK is older, point `JAVA_HOME` at a JDK 21 or later
  for the Maven commands.
- Docker must be running. Each run starts a throwaway PostgreSQL 17 with Testcontainers
  (`TestcontainersConfiguration`). **Tests never use the database or the Supabase project in
  `.env`**: they create and truncate data.
- Database and HTTP tests extend `DatabaseIntegrationTest`: one Spring context, one container, an
  embedded server on a random port, and every table truncated before each test.
- Tokens are signed by `TestTokens` with a key the application's real decoder accepts, so security
  is tested end to end without calling Supabase Auth.
- "Now" comes from the `Clock` bean. Tests move it with `MutableClock`, so rules such as "too late to
  cancel" are tested without waiting.
- Count the tests from the Surefire XML reports in `target/surefire-reports/`: the `.txt` summaries
  leave out nested test classes. On 2026-11-04: 341 tests, 0 failures.

Where to look, by area:

| Area | Test classes |
| --- | --- |
| Scheduling rules (pure domain, no Spring) | `scheduling/TimeRangeTest`, `BusinessDayTest`, `StartTimeRuleTest`, `AllocatorTest`, `SlotFinderTest` |
| Database guarantees | `schema/AppointmentConstraintsIntegrationTest`, `ConcurrentInsertIntegrationTest`, `ReferenceDataIntegrationTest`, `SchemaPlatformIntegrationTest`, `DemoDataIntegrationTest` |
| Booking | `appointment/AppointmentBookingIntegrationTest`, `BookingValidationIntegrationTest`, `AppointmentRepositoryIntegrationTest` |
| Concurrency | `appointment/service/BookingConcurrencyIntegrationTest`, `BookingFailuresTest` |
| Idempotency | `appointment/IdempotencyIntegrationTest`, `appointment/service/RequestFingerprintTest` |
| Reading, listing and cancelling | `AppointmentQueryIntegrationTest`, `AppointmentListIntegrationTest`, `AppointmentCancellationIntegrationTest` |
| Availability | `availability/AvailabilityIntegrationTest`, `AvailabilityBookingIntegrationTest` (search and booking agree) |
| Security | `security/AuthenticationIntegrationTest`, `BookingPermissionIntegrationTest`, `AppointmentVisibilityIntegrationTest`, `OperationalEndpointsIntegrationTest`, `AccessPolicyTest`, `SupabaseJwtConverterTest` |
| Catalog, profile, customer search | `catalog/*`, `account/AccountIntegrationTest`, `customer/*` |
| Errors, request id, metrics, OpenAPI | `web/error/*`, `web/filter/*`, `BookingMetricsIntegrationTest`, `web/OpenApiIntegrationTest` |

The concurrency tests are the ones behind the guarantee against double booking:

- `BookingConcurrencyIntegrationTest`:
  - 20 parallel requests for 2 free places give exactly 2 bookings and 18 `NO_AVAILABILITY`;
  - concurrent duplicates with one idempotency key create one appointment;
  - a lost race is retried on fresh data;
  - a request that waits too long for the lock gets `SLOT_CONTENTION`.
- `ConcurrentInsertIntegrationTest`: two connections insert overlapping rows directly, bypassing the
  application. The exclusion constraint still rejects the second (`23P01`), and the unique key does
  the same for a duplicate idempotency key (`23505`).

### Frontend

```bash
npm --prefix frontend test              # Vitest + Testing Library + MSW
npm --prefix frontend run test:watch
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

- Run the tools through `npm --prefix frontend` or from inside `frontend/`. A bare `npx vitest` from
  the repository root can pick up a global Vitest and fail for unrelated reasons.
- Tests render the real app and answer `/api/v1` with MSW. Any unhandled request fails the test. A
  fake `AuthClient` replaces Supabase, so the suite runs offline.
- `TZ=UTC` and `Date` is fixed, so times are checked in each dealership's own zone, whatever the
  machine's zone is.
- App-level tests are named after the scenarios in `openspec/changes/add-web-client/specs/web-client/spec.md`.
  On 2026-11-04: 13 files, 98 tests, all passing.

### Specs

```bash
openspec validate --all --strict
```

## 2. A live environment

The manual scenarios and the race demo run against the real stack. They need a Supabase project.

1. **Backend settings.** Copy `.env.example` to `.env` and fill in the database (session pooler,
   port 5432), `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` and `SUPABASE_SECRET_KEY`. The backend
   itself needs only the database and `SUPABASE_URL`; the scripts and cURL use the two keys.
2. **Web client settings.** Create `frontend/.env.local` with `VITE_SUPABASE_URL` and
   `VITE_SUPABASE_PUBLISHABLE_KEY` (see `frontend/.env.example`):
   - Never put the secret key there: everything prefixed `VITE_` ends up in the bundle.
   - `.env.local` wins over a `frontend/.env`.
   - Restart `npm run dev` after editing it.
3. **Start both.** `./mvnw spring-boot:run` (http://localhost:8080) and `npm --prefix frontend run dev`
   (http://localhost:5173). On first start, Flyway creates the schema and loads the demo data.
4. **Demo users.** This creates or updates them in Supabase Auth, with their role in `app_metadata`:

   ```bash
   DEMO_USER_PASSWORD='choose-one' python scripts/supabase-demo-users.py
   ```

5. **Demo appointments.** Books sample appointments through the API, so every screen has something
   to show. Re-running replays instead of booking twice.

   ```bash
   DEMO_USER_PASSWORD='choose-one' python scripts/demo-appointments.py
   ```

| Account | Role | Linked to |
| --- | --- | --- |
| `emma.wilson@example.com` | customer | Emma Wilson: VW Golf, Toyota Corolla |
| `minh.duc@example.com` | customer | Le Minh Duc: Ford Focus |
| `scheduler.advisor.saigon@example.com` | advisor | Saigon Auto Centre |
| `scheduler.admin@example.com` | admin | every dealership |

All four share the password given to the script. There is no sign-up: an administrator links an
account to a customer or dealership (see `docs/SYSTEM_DESIGN.md`, Assumptions).

Demo data, useful for predicting outcomes:

| Dealership | Hours, zone | Bays | Technicians (services) |
| --- | --- | --- | --- |
| Riverside Motors London | 08:00-18:00, Europe/London | Bay 1, Bay 2, Bay 3 | Alice Morgan (oil, brake, full); Ben Carter (oil, tyre); Chloe Evans (inactive) |
| Saigon Auto Centre | 07:30-17:30, Asia/Ho_Chi_Minh | Bay A, Bay B | Nguyen Van An (all four); Tran Thi Binh (oil, tyre) |

- **Services:** Oil and filter change (45 min), Tyre rotation (30), Brake service (90), Full service (180).
- **Start-time grid:** every 30 minutes from opening.
- **Bottleneck at Saigon:** only Nguyen Van An does brake and full services.

What `demo-appointments.py` books (D is today at Saigon):

| Day | Dealership | Appointments | Use it for |
| --- | --- | --- | --- |
| D | Saigon | Focus, tyre rotation, first free slot | The advisor's start page; once it has started, cancelling is refused |
| D+4 | Saigon | Three full services back to back, 07:30-16:30 | No brake or full service time is free that day; oil changes still are |
| D+5 | London | Two oil changes at 09:00, tyre rotation 10:00, brake service 14:00 | Two bays in use at once |
| D+6 | Saigon | Oil change 08:00, booked then cancelled | Emma's *Past or cancelled* list |

## 3. Manual scenarios (web client)

D is today at the dealership. Times are the dealership's local time, whatever your device's zone is.
Open DevTools (Network) to see requests.

**Sign-in and session**

| # | Steps | Expected |
| --- | --- | --- |
| A1 | Signed out, open http://localhost:5173/appointments | The sign-in page |
| A2 | Sign in as Emma with a wrong password | "E-mail or password is incorrect.", and the e-mail stays filled in |
| A3 | Sign in as Emma | *Book a service*, with *Book a service* and *My appointments* in the navigation |
| A4 | In DevTools → Application → Local Storage, change `access_token` inside `sb-<project-ref>-auth-token`, then do anything | Back to sign-in with "Your session has expired. Please sign in again." |

**Customer (Emma)**

| # | Steps | Expected |
| --- | --- | --- |
| B1 | Golf → *Oil and filter change* → Saigon, D+1 → 10:00 → Confirm | The confirmation shows bay, technician and "10:00–10:45 (Asia/Ho_Chi_Minh)". The service step lists four services with durations and no filter field |
| B2 | Corolla at London, D+1 | The first time offered is 08:00 (Europe/London), not converted to your zone |
| B3 | Press Confirm twice quickly | The button is disabled while the request runs; one `POST /api/v1/appointments` with an `Idempotency-Key` header |
| B4 | Golf again, Saigon, D+1, 10:30 | "This vehicle already has an appointment at that time." |
| B5 | *Brake service* at Saigon on D+4 | No time is free that day, with an offer to pick another date |
| B6 | *My appointments* → *Cancel* on an upcoming one → confirm | It moves to *Past or cancelled*, status cancelled, without a *Cancel* button |
| B7 | Emma in one window and Duc in a private window both reach Confirm for *Brake service*, Saigon, D+2, 09:00. Emma confirms, then Duc | Duc sees "That time was just taken. Please choose another.", is back at the time step, and 09:00 is gone from the reloaded list |
| B8 | As Duc, after the start of his appointment today, *Cancel* it | "This appointment has already started, so it can no longer be cancelled." |

**Advisor (Saigon)**

| # | Steps | Expected |
| --- | --- | --- |
| C1 | Sign in | Today's *Day schedule* for Saigon Auto Centre, no dealership picker; *Day schedule* and *Book for a customer* in the navigation |
| C2 | Go to D+4 with the arrows | Three full services in their bay row, with local times, technician and service |
| C3 | A day without appointments | The bays, with "No appointments on this day." |
| C4 | *Book for a customer*, type `d` | A hint asks for at least two characters; no request is sent |
| C5 | Type `duc`, `Đức`, `emma wilsn`, `minh le duc`, then `zzz` | The first four find a customer, ignoring accents, a typo and the word order; `zzz` finds nobody. A lone misspelt word (`wilsn`) is not similar enough to a full name, by design |
| C6 | Choose Le Minh Duc and his Focus, book at Saigon | Booked, and it appears on the day schedule |
| C7 | The same at London | "Your account is not allowed to do this." (`403`): an advisor books only at their own dealership, although the client offers every dealership |

**Admin**

| # | Steps | Expected |
| --- | --- | --- |
| D1 | Sign in, switch the dealership picker, go to D+5 at London | The schedule reloads per dealership; two bays are in use at 09:00 |
| D2 | *Book for a customer*: Emma at London | Booked |

**Accessibility and failures**

| # | Steps | Expected |
| --- | --- | --- |
| E1 | Book with the keyboard only (Tab, Enter, Space) | Every option and button is reachable, with a visible focus ring |
| E2 | DevTools device toolbar at 375 px wide | No horizontal scroll |
| E3 | DevTools → Rendering → emulate `prefers-reduced-motion: reduce` | No animations |
| E4 | At the confirmation step, stop the backend, press Confirm, start the backend, press *Try again* | The retry carries the same `Idempotency-Key`, and only one appointment exists |

## 4. API checks (Swagger UI or cURL)

Swagger UI is at http://localhost:8080/swagger-ui.html. Get a token as in `docs/SYSTEM_DESIGN.md`
(Getting a token), or copy `access_token` from the web client's local storage. Paste it into
*Authorize*.

```bash
DAY=$(date -d '+3 days' +%F)      # a day the demo data leaves free
curl -i -X POST http://localhost:8080/api/v1/appointments \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: try-001" \
  -d "{\"dealershipId\":2,\"customerId\":1,\"vehicleId\":1,\"serviceTypeId\":1,\"startTime\":\"${DAY}T10:00:00+07:00\"}"
```

On a fresh database the demo ids are:
- dealerships 1 London, 2 Saigon;
- service types 1 oil, 2 tyre, 3 brake, 4 full;
- customers 1 Emma, 2 Duc;
- vehicles 1 Golf, 2 Corolla, 3 Focus.

Check yours with `GET /api/v1/dealerships`, `GET /api/v1/service-types` and `GET /api/v1/customers?q=emma`.

| # | Change from the request above (admin token unless stated) | Expected |
| --- | --- | --- |
| 1 | None | `201` |
| 2 | Send it again unchanged | `201`, same id, header `Idempotent-Replayed: true` |
| 3 | Same key, `startTime` 10:30 | `422 IDEMPOTENCY_KEY_REUSED` |
| 4 | Without `serviceTypeId` | `400 VALIDATION_FAILED` with an `errors` entry |
| 5 | `startTime` without an offset | `400 VALIDATION_FAILED` |
| 6 | `dealershipId` 999 | `404 DEALERSHIP_NOT_FOUND` |
| 7 | `customerId` 1 with `vehicleId` 3 | `422 VEHICLE_NOT_OWNED_BY_CUSTOMER` |
| 8 | A start in the past | `422 START_TIME_IN_PAST` |
| 9 | Brake service (`3`) at 17:00, or any service at 07:00 | `422 OUTSIDE_OPENING_HOURS` |
| 10 | 10:15 | `422 START_TIME_NOT_ON_GRID` |
| 11 | Emma's token, `customerId` 2 | `403 FORBIDDEN` |
| 12 | The Saigon advisor's token, `dealershipId` 1 | `403 FORBIDDEN` |
| 13 | Emma's token, `GET /api/v1/appointments/{one of Duc's}` | `404 APPOINTMENT_NOT_FOUND` (not `403`) |
| 14 | `POST /api/v1/appointments/{id}/cancel` twice | `200 CANCELLED` both times |
| 15 | No token, `GET /api/v1/me` or the availability search | `401 UNAUTHENTICATED` |
| 16 | `GET /actuator/prometheus` with the admin's token, then Emma's | `200` containing `scheduler_bookings_total`, then `403` |

The Postman collection in `postman/` covers the same endpoints.

## 5. Live race demo

`scripts/race-demo.py` shows the concurrency and idempotency guarantees on the running stack.

**How it works:**
- Each race holds its requests at a barrier and releases them together.
- The report draws every request as a bar, from sending it to its response.
- It then reads the day back and checks every pair of confirmed appointments.

```bash
DEMO_USER_PASSWORD='choose-one' python scripts/race-demo.py            # report: target/race-demo/race-report.html
DEMO_USER_PASSWORD='choose-one' python scripts/race-demo.py --date 2026-12-04 --out target/race-demo/run.html
```

The demo owns one day: by default, 30 days from today. **It cancels every confirmed appointment on
that day at both demo dealerships before it starts.** That makes it repeatable, so do not point it
at a day with data you need.

| Race | Requests | Ideal outcome |
| --- | --- | --- |
| Capacity | 3 vehicles, oil change, Saigon 10:00 (2 bays, 2 technicians) | 2 booked, 1 `NO_AVAILABILITY` |
| Technician bottleneck | 3 vehicles, brake service, Saigon 14:00 (one qualified technician) | 1 booked, 2 `NO_AVAILABILITY` |
| One vehicle | 6 requests with different keys, Golf, London 09:00 | 1 booked, 5 `VEHICLE_ALREADY_BOOKED` |
| Idempotency | 8 requests with the same key and body | 8 × `201` with one id: 1 created, 7 `Idempotent-Replayed: true` |
| Edge cases, one after another | reused key, other offset notation, cancel then replay, no key twice | `422`, replay, `200 CANCELLED`, replay as `CANCELLED`, `201` then `409` |

The report separates two kinds of check:

- **Invariants must always hold:**
  - no overlapping appointments on a bay, a technician or a vehicle;
  - never more bookings than the capacity;
  - one key, one appointment;
  - failures persist nothing.

  A failed invariant is a bug.
- **Ideal outcomes** hold when no request times out waiting for the booking lock.

A request that waits longer than `scheduler.booking-lock-timeout` (3 s) gets
`409 SLOT_CONTENTION`. It fails fast and persists nothing, and the client should retry. You will see
it when one booking transaction takes long. The typical case is a backend far from its database,
such as a laptop calling a Supabase project on another continent: each statement then costs a
network round trip, and the third request in the queue already waits more than 3 s.

To see the business outcomes alone, start the backend with a longer timeout and label the report:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--scheduler.booking-lock-timeout=30s
DEMO_USER_PASSWORD='choose-one' python scripts/race-demo.py --lock-timeout 30s
```

On 2026-11-04 (backend in Vietnam, database in eu-west-1), every run held all 20 invariants. Runs
at 3 s had 2 to 7 `SLOT_CONTENTION` answers; a run at 30 s had none and every ideal outcome. Details
are in `docs/AI_LOG.md`.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Testcontainers cannot find Docker | Start Docker Desktop, then run the tests again |
| `release version 21 not supported` | Maven runs on an older JDK; set `JAVA_HOME` to JDK 21 or later |
| The sign-in page says sign-in is not configured | `frontend/.env.local` is missing or incomplete; restart `npm run dev` after creating it |
| Sign-in works but every API call answers `401` | `SUPABASE_URL` is missing from `.env`, so the backend fetches keys from the local default `127.0.0.1:54321`; add it and restart the backend |
| The web client talks to the wrong Supabase project | An old `frontend/.env` is used; `frontend/.env.local` overrides it |
| Port 8080 or 5173 already in use | An earlier run is still alive; stop that process (on Windows, the `java` or `node` child can outlive the terminal that started it) |
