## MODIFIED Requirements

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
