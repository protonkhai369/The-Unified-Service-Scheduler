# Appointment Booking Specification

## Purpose
Let a customer book a service appointment for one of their vehicles at a dealership and time of
their choosing, guaranteeing that a service bay and a qualified technician are reserved for the
entire service duration and that no resource is ever double-booked, even under concurrent load.
Covers Scenario A core requirements 1 (resource-constrained booking), 2 (real-time availability
check) and 3 (confirmed appointment record).

## Requirements

### Requirement: Book a service appointment
The system SHALL accept `POST /api/v1/appointments` with `dealershipId`, `customerId`,
`vehicleId`, `serviceTypeId` and `startTime`, and on success SHALL respond `201 Created` with a
`Location` header and the appointment, whose status MUST be `CONFIRMED`. The end time MUST equal
the start time plus the service type's duration.

#### Scenario: Successful booking
- **WHEN** a valid request is made for a time when a bay and a qualified technician are free
- **THEN** the response is `201 Created` with `Location: /api/v1/appointments/{id}`
- **AND** the body contains status `CONFIRMED`, the allocated service bay and technician (id and name), and start and end times

#### Scenario: End time derived from service duration
- **WHEN** a 90-minute service type is booked to start at 10:00 local time
- **THEN** the appointment ends at 11:30 local time

### Requirement: Bay and qualified technician available for the whole duration
Before confirming, the system MUST find one active service bay and one active technician
qualified for the requested service type that are both free for the entire requested period;
otherwise it SHALL respond `409 Conflict` with code `NO_AVAILABILITY` and persist nothing.

#### Scenario: Resource busy for part of the period
- **WHEN** the only bay is booked from 10:45 to 11:15 and a 60-minute service is requested at 10:00
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Bays free but no qualified technician free
- **WHEN** bays are free but every technician qualified for the service type is busy at that time
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Unqualified or inactive technicians are never allocated
- **WHEN** the only free technicians are inactive or not qualified for the service type
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Back-to-back bookings share resources
- **WHEN** a bay and technician are booked from 09:00 to 10:00 and a booking is requested from 10:00
- **THEN** the same bay and technician may be allocated, because ranges are half-open

### Requirement: No double booking under concurrent requests
The system MUST guarantee at the database level that no two `CONFIRMED` appointments overlap in
time for the same service bay or the same technician, regardless of request timing. When a
booking loses a race, the system SHALL re-run allocation on fresh data up to
`scheduler.max-booking-attempts` times before responding `409` with code `SLOT_CONTENTION`.

#### Scenario: Parallel requests exceeding capacity
- **WHEN** 20 requests for the same start time arrive concurrently and only 2 bay/technician pairs are free
- **THEN** exactly 2 appointments are confirmed
- **AND** every other request receives `409` with code `NO_AVAILABILITY` or `SLOT_CONTENTION`
- **AND** the database holds no overlapping confirmed appointments for any bay or technician

#### Scenario: Overlapping insert bypassing the application
- **WHEN** an overlapping `CONFIRMED` appointment for the same bay is inserted directly with SQL
- **THEN** PostgreSQL rejects it with an exclusion violation (SQLSTATE `23P01`)

### Requirement: Booking request validation
The system SHALL reject invalid booking requests without persisting anything: `400` with code
`VALIDATION_FAILED` for missing or malformed fields, `404` with a `<RESOURCE>_NOT_FOUND` code for
unknown references, and `422` for business rule violations.

#### Scenario: Missing field
- **WHEN** `serviceTypeId` is omitted
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `serviceTypeId`

#### Scenario: Start time without an offset
- **WHEN** `startTime` is sent as a local date-time without a zone offset
- **THEN** the response is `400`

#### Scenario: Unknown dealership
- **WHEN** `dealershipId` does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Vehicle belongs to another customer
- **WHEN** `vehicleId` is not owned by `customerId`
- **THEN** the response is `422` with code `VEHICLE_NOT_OWNED_BY_CUSTOMER`

#### Scenario: Start time in the past
- **WHEN** `startTime` is not after the current time
- **THEN** the response is `422` with code `START_TIME_IN_PAST`

#### Scenario: Service would run past closing time
- **WHEN** a 90-minute service is requested at 17:00 at a dealership that closes at 18:00 local time
- **THEN** the response is `422` with code `OUTSIDE_OPENING_HOURS`

### Requirement: Technician workload balancing
When several qualified technicians are free, the system SHALL allocate the one with the fewest
booked minutes on that local day, breaking ties by lowest technician id; among free bays it SHALL
allocate the lowest bay id.

#### Scenario: Lighter day wins
- **WHEN** technician A has 3 hours booked that day, technician B has 1 hour, and both are free at the requested time
- **THEN** technician B is allocated

### Requirement: Confirmed appointment record
The system MUST persist each confirmed appointment with its customer, vehicle, dealership,
service type, service bay, technician, start and end time, and SHALL return it from
`GET /api/v1/appointments/{id}` with times in the dealership's time zone.

#### Scenario: Retrieve a booked appointment
- **WHEN** a client requests `GET /api/v1/appointments/{id}` for an existing appointment
- **THEN** the response is `200` with the same data returned at booking time

#### Scenario: Unknown appointment
- **WHEN** a client requests an appointment id that does not exist
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`

### Requirement: Idempotent booking retries
The system SHALL accept an optional `Idempotency-Key` header (1-100 characters). A repeated
request with the same key and identical booking parameters MUST return the original appointment
without booking again; the same key with different parameters MUST be rejected.

#### Scenario: Safe retry after a timeout
- **WHEN** a booking request is repeated with the same `Idempotency-Key` and the same body
- **THEN** the response is `201` with the original appointment id and header `Idempotent-Replayed: true`
- **AND** only one appointment exists

#### Scenario: Key reused for a different booking
- **WHEN** an `Idempotency-Key` is reused with a different start time
- **THEN** the response is `422` with code `IDEMPOTENCY_KEY_REUSED`

#### Scenario: Concurrent duplicates
- **WHEN** two requests with the same key and body arrive at the same time
- **THEN** exactly one appointment is created and both responses carry its id

### Requirement: Appointment cancellation
The system SHALL cancel an appointment via `POST /api/v1/appointments/{id}/cancel`, setting its
status to `CANCELLED` and releasing its bay and technician. Cancelling an already cancelled
appointment MUST succeed without changes.

#### Scenario: Cancellation frees the slot
- **WHEN** a confirmed appointment is cancelled
- **THEN** its status is `CANCELLED`
- **AND** a new booking for the same bay, technician and time can be confirmed

#### Scenario: Cancel twice
- **WHEN** an already cancelled appointment is cancelled again
- **THEN** the response is `200` with status `CANCELLED`
