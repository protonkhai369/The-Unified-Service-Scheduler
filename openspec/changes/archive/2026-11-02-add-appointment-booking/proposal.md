## Why

Booking is the core of Scenario A, and none of it exists yet. A customer must be able to request an
appointment for a vehicle, service type, dealership and time (core requirement 1). The service bay
and a qualified technician must both be checked free for the whole duration before confirming (core
requirement 2). The result must be persisted as an appointment that links customer, vehicle,
technician and bay (core requirement 3).

The database already rejects double bookings with exclusion constraints. What is missing is
everything around them: allocation, retries, idempotent replays, cancellation, and an API that turns
database conflicts into clear answers. That API must also stay correct and fair when many customers
book the same dealership at once, which serves the "build for the future" concerns of reliability,
scalability and observability.

## What Changes

- `POST /api/v1/appointments` books an appointment and responds `201 Created` with a `Location`
  header.
  - It checks that the referenced dealership, customer, vehicle and service type exist, and that
    the vehicle belongs to the customer.
  - It rejects a start time that is in the past, outside opening hours, or off the
    `scheduler.slot-step` grid. The grid and the opening hours are evaluated in the dealership's
    local time, whatever offset the client sent.
  - It allocates an active bay and an active, qualified technician that are both free for the whole
    duration. The bay with the lowest id wins; among technicians, the one with the fewest booked
    minutes that local day wins.
- Peak load: bookings for the same dealership and local day are processed one at a time, inside the
  database. While capacity remains, concurrent requests therefore succeed instead of failing with
  `SLOT_CONTENTION`. When capacity runs out, the remaining requests get `NO_AVAILABILITY` at once.
  The exclusion constraints stay the guarantee. A lost race, which can only happen when something
  bypasses the queue, is retried on fresh data up to `scheduler.max-booking-attempts` times (default
  5).
- Idempotency:
  - The optional `Idempotency-Key` header is honoured; without it, the server generates a key.
  - A repeated key with the same parameters (the same start instant in any offset) replays the
    stored appointment in its current state, with `Idempotent-Replayed: true`. The replay check runs
    before the start-time rules, so a retry after the start time has passed still gets its
    appointment back.
  - Reusing a key with different parameters is `422 IDEMPOTENCY_KEY_REUSED`.
- A vehicle cannot hold two overlapping confirmed appointments, even at two dealerships in different
  time zones; the second request gets `409 VEHICLE_ALREADY_BOOKED`.
- `GET /api/v1/appointments/{id}` returns an appointment. Times are given in the dealership's time
  zone, and the zone id is included.
- `POST /api/v1/appointments/{id}/cancel` cancels an appointment and frees its bay and technician.
  Cancelling again succeeds unchanged. Cancelling once the start time has been reached is
  `409 APPOINTMENT_ALREADY_STARTED`.
- New error codes, each defined in the booking spec: `SERVICE_TYPE_NOT_FOUND`,
  `CUSTOMER_NOT_FOUND`, `VEHICLE_NOT_FOUND`, `APPOINTMENT_NOT_FOUND`,
  `VEHICLE_NOT_OWNED_BY_CUSTOMER`, `START_TIME_IN_PAST`, `START_TIME_NOT_ON_GRID`,
  `OUTSIDE_OPENING_HOURS`, `IDEMPOTENCY_KEY_REUSED`, `NO_AVAILABILITY`, `SLOT_CONTENTION`,
  `VEHICLE_ALREADY_BOOKED`, `APPOINTMENT_ALREADY_STARTED`.
- Booking metrics, as already specified in `observability`: `scheduler_bookings_total{outcome}` and
  `scheduler_booking_conflicts_total`. Which outcome each response counts as is made precise.
- A pure scheduling domain (time ranges, business day, slot grid, allocation) with no Spring or JPA.
  The later availability search reuses it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `appointment-booking`: adds the server-generated idempotency key, the replay-before-rules order,
  replay of cancelled appointments, the slot grid (`START_TIME_NOT_ON_GRID`), the
  `CUSTOMER_NOT_FOUND`, `VEHICLE_NOT_FOUND` and `SERVICE_TYPE_NOT_FOUND` codes,
  `VEHICLE_ALREADY_BOOKED`, `APPOINTMENT_ALREADY_STARTED` (409), and per-dealership-day queuing.
  Within capacity, concurrent requests all succeed; beyond it, they get `NO_AVAILABILITY`.
  It also adds the time-zone scenarios (requests in different offsets, a client offset of +05:45,
  one vehicle at dealerships in two zones), the response time zone, and a default of 5 attempts.
- `observability`: the "Booking metrics" requirement defines which responses count as `confirmed`,
  `replayed`, `no_availability`, `contention`, `rejected` and `error`.

## Non-goals

- The availability search endpoint (`availability-search`). It is the next change and reuses this
  change's domain.
- Listing or searching appointments, rescheduling, and customer or vehicle endpoints. Customers and
  vehicles are only looked up.
- Technician shifts, time off, bay types, holidays, or opening hours that differ by day.
- Authentication; scoping or expiring idempotency keys per client.
- Pushing "slot taken" notifications to clients (SSE/WebSocket); that belongs to a frontend.
- Dockerfile, docker compose, and the README rewrite with cURL walkthroughs and the AI collaboration
  narrative. These are later changes.
- Changing the log level of 4xx problems (WARN today). This is raised as an open question in the
  design.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- Start times lie on a grid of `scheduler.slot-step` (default 30 minutes), anchored at the
  dealership's opening time in its local time zone.
- An appointment must fit inside one day's opening hours, so it never crosses local midnight.
- A start time equal to the current instant is already in the past. Cancellation is allowed only
  strictly before the start time.
- `startTime` must carry an offset (`Z` or `±hh:mm`). A local date-time without an offset is
  ambiguous and rejected.
- Two requests carry "the same booking parameters" when they have the same dealership, customer,
  vehicle and service type and the same start instant. The offset notation does not matter.
- Replaying the key of a cancelled appointment returns that appointment as it is now (`CANCELLED`),
  not a new booking.
- Vehicle ownership is checked when booking. A later change of owner does not affect existing
  appointments.
- Idempotency keys are global and live as long as their appointment (there is no client identity
  yet).

## Impact

- New code:
  - `scheduling`: the pure domain.
  - `appointment`: controller, services, repository, entity, request and response records.
  - `customer`: read-only `Customer` and `Vehicle` entities and repositories.
  - `config`: `SchedulerProperties` (`scheduler.max-booking-attempts`, `scheduler.slot-step`,
    `scheduler.booking-lock-timeout`) and a `Clock` bean.
- Changed:
  - `ErrorCode`: 13 new constants.
  - `application.yml`: the scheduler properties.
  - Test support: `DatabaseFixtures`, and a controllable clock in `ProbeConfiguration`.
  - Docs and project context: `docs/SYSTEM_DESIGN.md` (booking flow, concurrency layers,
    assumptions), `docs/AI_LOG.md`, `AGENTS.md` and `openspec/config.yaml` (current state; the
    per-dealership-day lock).
- APIs: three new endpoints under `/api/v1/appointments`.
- No schema change: V1 already has the appointment table, its constraints, the idempotency key
  and the fingerprint.
- No new dependencies: Micrometer, the Prometheus registry and the PostgreSQL driver are already
  present.
