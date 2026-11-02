# Observability Specification

## Purpose
Make the service operable in production: every request traceable through its logs, booking
health measurable through business metrics, and liveness/readiness checkable by an orchestrator
such as Kubernetes or a load balancer.
## Requirements
### Requirement: Request correlation id
The system SHALL assign every API request a correlation id, reusing the caller's `X-Request-Id`
header when it matches `[A-Za-z0-9._-]{1,64}` and generating a UUID otherwise. The id MUST be
returned in the `X-Request-Id` response header and included in every log line for that request.

#### Scenario: Caller-supplied id is propagated
- **WHEN** a request carries `X-Request-Id: abc-123`
- **THEN** the response carries `X-Request-Id: abc-123` and the request's log lines include `abc-123`

#### Scenario: Unsafe id is replaced
- **WHEN** a request carries an `X-Request-Id` containing spaces or longer than 64 characters
- **THEN** the response carries a newly generated UUID instead

### Requirement: Access log
The system SHALL write one log line per API request with HTTP method, path, response status and
duration in milliseconds.

#### Scenario: Request logged
- **WHEN** a booking request completes
- **THEN** a log line such as `POST /api/v1/appointments -> 201 (35 ms)` is written

### Requirement: Booking metrics
The system SHALL export Prometheus metrics at `/actuator/prometheus`. They SHALL include:
- `scheduler_bookings_total`, tagged by `outcome` (`confirmed`, `replayed`, `no_availability`,
  `contention`, `rejected`, `error`);
- `scheduler_booking_conflicts_total`, counting allocation attempts that lost a race;
- the standard `http_server_requests_seconds` latency histogram, with buckets.

Every booking request that passes request validation SHALL increase `scheduler_bookings_total`
exactly once, under one of these outcomes:
- `confirmed`: a new appointment;
- `replayed`: an idempotent replay;
- `no_availability`: code `NO_AVAILABILITY`;
- `contention`: code `SLOT_CONTENTION`;
- `rejected`: any other booking error code (an unknown reference, a business rule,
  `IDEMPOTENCY_KEY_REUSED` or `VEHICLE_ALREADY_BOOKED`);
- `error`: an unexpected failure answered with `500` `INTERNAL_ERROR`.

Requests rejected with `400` `VALIDATION_FAILED` are counted only by `http_server_requests_seconds`.

#### Scenario: Confirmed booking counted
- **WHEN** a booking is confirmed
- **THEN** `scheduler_bookings_total{outcome="confirmed"}` increases by one

#### Scenario: Replay counted
- **WHEN** a booking request is replayed through its `Idempotency-Key`
- **THEN** `scheduler_bookings_total{outcome="replayed"}` increases by one and `confirmed` does not change

#### Scenario: Business rule rejection counted
- **WHEN** a booking is rejected with `VEHICLE_NOT_OWNED_BY_CUSTOMER`
- **THEN** `scheduler_bookings_total{outcome="rejected"}` increases by one

#### Scenario: Unexpected failure counted
- **WHEN** a validated booking request fails unexpectedly, for example because the dealership's stored time zone is not a valid IANA zone
- **THEN** the response is `500` with code `INTERNAL_ERROR`
- **AND** `scheduler_bookings_total{outcome="error"}` increases by one

#### Scenario: Malformed request not counted as a booking
- **WHEN** a booking request is rejected with `400` `VALIDATION_FAILED`
- **THEN** no `scheduler_bookings_total` series changes

#### Scenario: Lost race counted
- **WHEN** an allocation attempt is rejected by the database overlap constraint and retried
- **THEN** `scheduler_booking_conflicts_total` increases by one

#### Scenario: Latency histogram buckets
- **WHEN** `/actuator/prometheus` is scraped after an API request
- **THEN** the output contains `http_server_requests_seconds_bucket` series

### Requirement: Health probes
The system SHALL expose `/actuator/health`, `/actuator/health/liveness` and
`/actuator/health/readiness`; readiness MUST report DOWN when the database is unreachable.

#### Scenario: Database down
- **WHEN** PostgreSQL is unreachable
- **THEN** `/actuator/health` reports status `DOWN`

### Requirement: Structured logs on demand
The system SHALL emit JSON (ECS) logs when `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` is set, with
the request id as a field, and human-readable logs otherwise.

#### Scenario: Container deployment
- **WHEN** the service runs via docker compose
- **THEN** each log line is a single JSON object containing the request id
