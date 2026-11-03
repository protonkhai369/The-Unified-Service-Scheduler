## ADDED Requirements

### Requirement: Caller's appointments
`GET /api/v1/me/appointments` SHALL return every appointment of the caller's customer, at every
dealership and in both statuses (`CONFIRMED` and `CANCELLED`), as `{"items": [...]}`. Items SHALL be
ordered by start time, then id. Each item SHALL have the shape that `GET /api/v1/appointments/{id}`
returns, with times in its own dealership's offset. Only a `CUSTOMER` with a `customer_id` may call
it. Any other authenticated caller SHALL receive `403` with code `FORBIDDEN`.

#### Scenario: Customer sees only their own appointments
- **WHEN** Emma has one appointment in London at `2026-11-04T10:00:00Z` and one in Saigon at `2026-11-03T09:00:00+07:00`, Duc has one appointment, and Emma calls `GET /api/v1/me/appointments`
- **THEN** the response is `200` with Emma's two appointments, Saigon first because it starts earlier
- **AND** the Saigon item's `startTime` is `2026-11-03T09:00:00+07:00` and the London item's is `2026-11-04T10:00:00Z`
- **AND** Duc's appointment is not listed

#### Scenario: Cancelled appointments stay in the list
- **WHEN** a customer cancels one of their appointments and then lists their appointments
- **THEN** that appointment is listed with `status` `CANCELLED`

#### Scenario: Customer without appointments
- **WHEN** a customer with no appointment calls `GET /api/v1/me/appointments`
- **THEN** the response is `200` with an empty `items` array

#### Scenario: Staff have no appointments of their own
- **WHEN** an advisor or an admin calls `GET /api/v1/me/appointments`
- **THEN** the response is `403` with code `FORBIDDEN`

### Requirement: Dealership day schedule
`GET /api/v1/dealerships/{dealershipId}/appointments?date=` SHALL return that dealership's
`CONFIRMED` appointments whose start falls on `date`, an ISO local date read in the
dealership's time zone. The result SHALL be `{"items": [...]}`, ordered by start time, then service
bay id. Each item SHALL have the shape that `GET /api/v1/appointments/{id}` returns.

Only an `ADVISOR` of that dealership or an `ADMIN` may call it. Any other authenticated caller SHALL
receive `403` with code `FORBIDDEN`. For an allowed caller, an unknown dealership SHALL answer `404`
with code `DEALERSHIP_NOT_FOUND`. A missing or malformed `date`, or a non-positive `dealershipId`,
SHALL answer `400` with code `VALIDATION_FAILED`.

#### Scenario: Advisor sees their dealership's day
- **WHEN** the Saigon dealership has appointments at 10:00 and 08:00 local time on 3 November and one on 4 November, and its advisor requests `date=2026-11-03`
- **THEN** the response is `200` with the 08:00 and 10:00 appointments, in that order

#### Scenario: Local day, not UTC day
- **WHEN** a dealership in `Pacific/Auckland` (UTC+13 in November) has an appointment at 08:00 local time on 3 November, which is `2026-11-02T19:00:00Z`
- **THEN** it is listed for `date=2026-11-03` and not for `date=2026-11-02`

#### Scenario: Other dealerships are not mixed in
- **WHEN** London and Saigon both have appointments on 3 November and an admin requests Saigon's day
- **THEN** only Saigon's appointments are listed

#### Scenario: Cancelled appointments are left out
- **WHEN** an appointment on the requested day was cancelled
- **THEN** it is not listed, because it no longer occupies a bay or a technician

#### Scenario: A confirmed booking is visible at once
- **WHEN** a booking has answered `201`, and the advisor then requests that day
- **THEN** the new appointment is listed

#### Scenario: Advisor of another dealership
- **WHEN** an advisor of London requests Saigon's day
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Customer requests a dealership's day
- **WHEN** a customer requests any dealership's day
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Admin and unknown dealership
- **WHEN** an admin requests the day of dealership 999, which does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Missing date
- **WHEN** an admin requests `GET /api/v1/dealerships/1/appointments` without `date`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `date`

#### Scenario: Schedule without a token
- **WHEN** a dealership's day is requested without a token
- **THEN** the response is `401` with code `UNAUTHENTICATED`
