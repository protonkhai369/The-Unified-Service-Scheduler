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
The system SHALL export Prometheus metrics at `/actuator/prometheus`, including
`scheduler_bookings_total` tagged by `outcome` (`confirmed`, `replayed`, `no_availability`,
`contention`, `rejected`), `scheduler_booking_conflicts_total` (allocation attempts that lost a
race), and the standard `http_server_requests_seconds` latency histogram.

#### Scenario: Confirmed booking counted
- **WHEN** a booking is confirmed
- **THEN** `scheduler_bookings_total{outcome="confirmed"}` increases by one

#### Scenario: Lost race counted
- **WHEN** an allocation attempt is rejected by the database overlap constraint and retried
- **THEN** `scheduler_booking_conflicts_total` increases by one

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
