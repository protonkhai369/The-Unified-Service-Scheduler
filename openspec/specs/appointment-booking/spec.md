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
the start time plus the service type's duration. The appointment MUST be persisted before the
response is sent.

#### Scenario: Successful booking
- **WHEN** a valid request is made for a time when a bay and a qualified technician are free
- **THEN** the response is `201 Created` with `Location: /api/v1/appointments/{id}`
- **AND** the body contains `id`, status `CONFIRMED`, `dealershipId`, `customerId`, `vehicleId`, `serviceTypeId`, the allocated `serviceBay` and `technician` (each with `id` and `name`), `startTime`, `endTime` and `timeZone`

#### Scenario: End time derived from service duration
- **WHEN** a 90-minute service type is booked to start at 10:00 local time
- **THEN** the appointment ends at 11:30 local time

### Requirement: Bay and qualified technician available for the whole duration
Before confirming, the system MUST find one active service bay and one active technician with an
active qualification for the requested service type, both belonging to the requested dealership and
both free for the entire requested period. Otherwise it SHALL respond `409 Conflict` with code
`NO_AVAILABILITY` and persist nothing. Only `CONFIRMED` appointments occupy a bay or a technician.

#### Scenario: Resource busy for part of the period
- **WHEN** the only bay is booked from 10:45 to 11:15 and a 60-minute service is requested at 10:00
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Bays free but no qualified technician free
- **WHEN** bays are free but every technician qualified for the service type is busy at that time
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Unqualified or inactive technicians are never allocated
- **WHEN** the only free technicians are inactive, not qualified for the service type, or qualified only through a deactivated qualification
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Inactive bays are never allocated
- **WHEN** the only free bay is inactive
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Resources of another dealership are never allocated
- **WHEN** the requested dealership's bays are all busy and another dealership has a free bay and a free qualified technician
- **THEN** the response is `409` with code `NO_AVAILABILITY`

#### Scenario: Back-to-back bookings share resources
- **WHEN** a bay and technician are booked from 09:00 to 10:00 and a booking is requested from 10:00
- **THEN** the same bay and technician may be allocated, because ranges are half-open

#### Scenario: Cancelled appointments do not occupy resources
- **WHEN** the only bay and technician had an appointment at the requested time that was cancelled
- **THEN** the booking is confirmed on that bay and technician

### Requirement: No double booking under concurrent requests
The system MUST guarantee at the database level that no two `CONFIRMED` appointments overlap in
time for the same service bay or the same technician, regardless of request timing. Booking
requests for the same dealership and the same local day SHALL be processed one at a time. Each
request therefore sees every booking confirmed before it, and concurrent requests MUST NOT fail
because of each other while a qualified technician and a bay remain free. A request that has waited
longer than `scheduler.booking-lock-timeout` (default 3 seconds) for its turn SHALL receive `409`
with code `SLOT_CONTENTION`. A booking can still lose a race against an appointment written without
waiting its turn (for example, directly in SQL). In that case the system SHALL re-run allocation on
fresh data, up to `scheduler.max-booking-attempts` times (default 5), before responding `409` with
code `SLOT_CONTENTION`.

#### Scenario: Parallel requests exceeding capacity
- **WHEN** 20 requests for 20 different vehicles and the same start time arrive concurrently and only 2 bay/technician pairs are free
- **THEN** exactly 2 appointments are confirmed
- **AND** each of the other 18 requests receives `409` with code `NO_AVAILABILITY`
- **AND** the database holds no overlapping confirmed appointments for any bay or technician

#### Scenario: Parallel requests within capacity
- **WHEN** 4 requests for 4 different vehicles and the same start time arrive concurrently and 4 bays and 4 qualified technicians are free
- **THEN** all 4 appointments are confirmed, each with a different bay and a different technician

#### Scenario: Concurrent requests in different offsets
- **WHEN** one bay/technician pair is free and two requests for different vehicles arrive concurrently, one with `startTime` `2026-11-03T17:00:00+07:00` and the other with `2026-11-03T10:00:00Z`
- **THEN** exactly one appointment is confirmed and the other request receives `409` with code `NO_AVAILABILITY`

#### Scenario: Waiting too long for its turn
- **WHEN** a booking for the same dealership and local day is still in progress after `scheduler.booking-lock-timeout`
- **THEN** the waiting request receives `409` with code `SLOT_CONTENTION` and nothing is persisted for it

#### Scenario: Lost race is retried on fresh data
- **WHEN** another writer commits an overlapping confirmed appointment on the bay a booking has just allocated, before the booking's insert completes, and a second bay is free
- **THEN** the booking is retried and confirmed on the second bay

#### Scenario: Overlapping insert bypassing the application
- **WHEN** an overlapping `CONFIRMED` appointment for the same bay is inserted directly with SQL
- **THEN** PostgreSQL rejects it with an exclusion violation (SQLSTATE `23P01`)

### Requirement: Booking request validation
The system SHALL reject invalid booking requests without persisting anything:
- `400` with code `VALIDATION_FAILED` for missing or malformed fields or a malformed
  `Idempotency-Key` header;
- `404` with code `DEALERSHIP_NOT_FOUND`, `CUSTOMER_NOT_FOUND`, `VEHICLE_NOT_FOUND` or
  `SERVICE_TYPE_NOT_FOUND` for unknown references;
- `422` for business rule violations: `VEHICLE_NOT_OWNED_BY_CUSTOMER`, `START_TIME_IN_PAST`,
  `OUTSIDE_OPENING_HOURS` and `START_TIME_NOT_ON_GRID`.

When a request breaks several of these rules, the system SHALL report the first one in this order:
unknown dealership, customer, vehicle, service type; vehicle ownership; start time in the past;
outside opening hours; off the grid.

#### Scenario: Missing field
- **WHEN** `serviceTypeId` is omitted
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `serviceTypeId`

#### Scenario: Start time without an offset
- **WHEN** `startTime` is sent as a local date-time without a zone offset
- **THEN** the response is `400` with code `VALIDATION_FAILED`

#### Scenario: Malformed idempotency key
- **WHEN** the `Idempotency-Key` header contains a space or is longer than 100 characters
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `Idempotency-Key`

#### Scenario: Unknown dealership
- **WHEN** `dealershipId` does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Unknown customer
- **WHEN** `customerId` does not exist
- **THEN** the response is `404` with code `CUSTOMER_NOT_FOUND`

#### Scenario: Unknown vehicle
- **WHEN** `vehicleId` does not exist
- **THEN** the response is `404` with code `VEHICLE_NOT_FOUND`

#### Scenario: Unknown service type
- **WHEN** `serviceTypeId` does not exist
- **THEN** the response is `404` with code `SERVICE_TYPE_NOT_FOUND`

#### Scenario: Vehicle belongs to another customer
- **WHEN** `vehicleId` is not owned by `customerId`
- **THEN** the response is `422` with code `VEHICLE_NOT_OWNED_BY_CUSTOMER`

#### Scenario: Start time in the past
- **WHEN** `startTime` is not after the current time
- **THEN** the response is `422` with code `START_TIME_IN_PAST`

#### Scenario: Service would run past closing time
- **WHEN** a 90-minute service is requested at 17:00 at a dealership that closes at 18:00 local time
- **THEN** the response is `422` with code `OUTSIDE_OPENING_HOURS`

#### Scenario: Start before opening time
- **WHEN** a service is requested at 07:00 at a dealership that opens at 08:00 local time
- **THEN** the response is `422` with code `OUTSIDE_OPENING_HOURS`

#### Scenario: Start time off the slot grid
- **WHEN** a service is requested at 10:15 local time at a dealership that opens at 08:00, with a 30-minute `scheduler.slot-step`
- **THEN** the response is `422` with code `START_TIME_NOT_ON_GRID`

### Requirement: Technician workload balancing
When several qualified technicians are free, the system SHALL allocate the one with the fewest
minutes of `CONFIRMED` appointments on that local day at the dealership, breaking ties by lowest
technician id. Among free bays it SHALL allocate the lowest bay id. Cancelled appointments MUST NOT
count towards a technician's booked minutes.

#### Scenario: Lighter day wins
- **WHEN** technician A has 3 hours booked that day, technician B has 1 hour, and both are free at the requested time
- **THEN** technician B is allocated

#### Scenario: Cancelled work does not count
- **WHEN** technician A's only appointment that day, of 3 hours, was cancelled, technician B has 1 hour booked, and both are free at the requested time
- **THEN** technician A is allocated

#### Scenario: Ties broken by technician id
- **WHEN** two free qualified technicians have the same booked minutes that day
- **THEN** the one with the lower id is allocated

#### Scenario: Lowest free bay wins
- **WHEN** bays 1 and 3 are free and bay 2 is busy at the requested time
- **THEN** bay 1 is allocated

### Requirement: Confirmed appointment record
The system MUST persist each confirmed appointment with its customer, vehicle, dealership, service
type, service bay, technician, start and end time, idempotency key and request fingerprint. It SHALL
return the appointment in its current state from `GET /api/v1/appointments/{id}`, with times in the
dealership's time zone.

#### Scenario: Retrieve a booked appointment
- **WHEN** a client requests `GET /api/v1/appointments/{id}` for an existing appointment
- **THEN** the response is `200` with the same data returned at booking time

#### Scenario: Retrieve a cancelled appointment
- **WHEN** a client requests an appointment that has been cancelled
- **THEN** the response is `200` with status `CANCELLED`

#### Scenario: Unknown appointment
- **WHEN** a client requests an appointment id that does not exist
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`

#### Scenario: Malformed appointment id
- **WHEN** a client requests `GET /api/v1/appointments/abc`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `id`

### Requirement: Idempotent booking retries
The system SHALL accept an optional `Idempotency-Key` header of 1 to 100 characters from
`A-Z a-z 0-9 . _ : -`; when the header is absent, the system SHALL generate a unique key. Two
requests have the same booking parameters when they name the same dealership, customer, vehicle and
service type and the same start instant, whatever offset notation was used. A repeated request with
the same key and the same booking parameters MUST return the stored appointment in its current state
with `201` and the header `Idempotent-Replayed: true`, without booking again. This check MUST happen
before any start-time rule or availability is evaluated. The same key with different parameters MUST
be rejected with `422` and code `IDEMPOTENCY_KEY_REUSED`.

#### Scenario: Safe retry after a timeout
- **WHEN** a booking request is repeated with the same `Idempotency-Key` and the same body
- **THEN** the response is `201` with the original appointment id and header `Idempotent-Replayed: true`
- **AND** only one appointment exists

#### Scenario: Same instant in another offset
- **WHEN** a booking made with `startTime` `2026-11-03T10:00:00+07:00` is repeated with the same key and `startTime` `2026-11-03T03:00:00Z`
- **THEN** the response is `201` with the original appointment id and header `Idempotent-Replayed: true`

#### Scenario: Retry after the start time has passed
- **WHEN** a confirmed booking is repeated with the same key and body after its start time has passed
- **THEN** the response is `201` with the original appointment and header `Idempotent-Replayed: true`, not `START_TIME_IN_PAST`

#### Scenario: Replay of a cancelled appointment
- **WHEN** a booking is cancelled and the original request is then repeated with the same key and body
- **THEN** the response is `201` with the original appointment id, status `CANCELLED` and header `Idempotent-Replayed: true`
- **AND** no new appointment is created

#### Scenario: Key reused for a different booking
- **WHEN** an `Idempotency-Key` is reused with a different start time
- **THEN** the response is `422` with code `IDEMPOTENCY_KEY_REUSED`

#### Scenario: Concurrent duplicates
- **WHEN** two requests with the same key and body arrive at the same time
- **THEN** exactly one appointment is created and both responses carry its id

#### Scenario: Requests without a key are not deduplicated
- **WHEN** the same booking request is sent twice without an `Idempotency-Key`
- **THEN** the first response is `201`
- **AND** the second is `409` with code `VEHICLE_ALREADY_BOOKED`, because the vehicle is already booked at that time

### Requirement: Appointment cancellation
The system SHALL cancel an appointment via `POST /api/v1/appointments/{id}/cancel`. Cancelling sets
the status to `CANCELLED`, releases the bay and technician, and responds `200` with the appointment.
Cancelling an already cancelled appointment MUST succeed without changes. A `CONFIRMED` appointment
whose start time has been reached MUST NOT be cancelled; the system SHALL respond `409` with code
`APPOINTMENT_ALREADY_STARTED` and leave it unchanged. An unknown id SHALL yield `404` with code
`APPOINTMENT_NOT_FOUND`.

#### Scenario: Cancellation frees the slot
- **WHEN** a confirmed appointment is cancelled
- **THEN** its status is `CANCELLED`
- **AND** a new booking for the same bay, technician and time can be confirmed

#### Scenario: Cancel twice
- **WHEN** an already cancelled appointment is cancelled again
- **THEN** the response is `200` with status `CANCELLED`

#### Scenario: Cancel after the start time
- **WHEN** a confirmed appointment is cancelled at or after its start time
- **THEN** the response is `409` with code `APPOINTMENT_ALREADY_STARTED`
- **AND** the appointment remains `CONFIRMED`

#### Scenario: Concurrent cancellations
- **WHEN** two cancellations of the same confirmed appointment arrive at the same time
- **THEN** both responses are `200` with status `CANCELLED`

#### Scenario: Cancel an unknown appointment
- **WHEN** a client cancels an appointment id that does not exist
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`

### Requirement: One booking per vehicle at a time
A vehicle MUST NOT hold two overlapping `CONFIRMED` appointments, whether at the same dealership or
at different ones. A booking that would overlap one SHALL be rejected with `409` and code
`VEHICLE_ALREADY_BOOKED`, and nothing SHALL be persisted, including when only the database detects
the conflict. Overlap is decided on instants, not on local clock times.

#### Scenario: Same vehicle at the same time
- **WHEN** a vehicle has a confirmed appointment from 10:00 to 11:00 and another booking for that vehicle is requested at 10:30 at the same dealership
- **THEN** the response is `409` with code `VEHICLE_ALREADY_BOOKED`

#### Scenario: Same instant at dealerships in different time zones
- **WHEN** a vehicle is booked at a `Europe/London` dealership at `2026-11-03T09:30:00Z` and a 45-minute booking for the same vehicle is requested at an `Asia/Ho_Chi_Minh` dealership at `2026-11-03T16:30:00+07:00`
- **THEN** the response is `409` with code `VEHICLE_ALREADY_BOOKED`, because both start at the same instant

#### Scenario: Same clock time is not the same instant
- **WHEN** a vehicle is booked at a `Europe/London` dealership at 09:30 local time and a booking for the same vehicle is requested at an `Asia/Ho_Chi_Minh` dealership at 09:30 local time on the same date
- **THEN** both bookings are confirmed, because they are seven hours apart

#### Scenario: Concurrent bookings of one vehicle at two dealerships
- **WHEN** two requests for the same vehicle and the same instant arrive concurrently for two different dealerships
- **THEN** exactly one appointment is confirmed and the other request receives `409` with code `VEHICLE_ALREADY_BOOKED`

### Requirement: Booking times in the dealership's time zone
`startTime` MAY use any offset. The system SHALL compare times as instants, and SHALL evaluate the
opening hours, the slot grid and the local day in the dealership's IANA time zone, including on
days when daylight saving time starts or ends. Responses SHALL express `startTime` and `endTime`
with the dealership's offset at that instant and SHALL include the dealership's `timeZone`.

#### Scenario: Client in another offset
- **WHEN** a client books an `Asia/Ho_Chi_Minh` dealership with `startTime` `2026-11-03T08:45:00+05:45`, which is 10:00 at the dealership, with a 30-minute slot step
- **THEN** the response is `201` with `startTime` `2026-11-03T10:00:00+07:00` and `timeZone` `Asia/Ho_Chi_Minh`

#### Scenario: Grid evaluated at the dealership
- **WHEN** a client books an `Asia/Ho_Chi_Minh` dealership with `startTime` `2026-11-03T10:00:00+05:45`, which is 11:15 at the dealership
- **THEN** the response is `422` with code `START_TIME_NOT_ON_GRID`

#### Scenario: Opening hours follow daylight saving time
- **WHEN** a 60-minute service is requested at a `Europe/London` dealership open 08:00-18:00, at `2026-03-29T17:00:00Z`, on the day British Summer Time starts
- **THEN** the response is `422` with code `OUTSIDE_OPENING_HOURS`, because that instant is 18:00 local time

#### Scenario: Booking on a daylight saving day
- **WHEN** a 60-minute service is requested at the same dealership at `2026-03-29T17:00:00+01:00`
- **THEN** the response is `201` with `startTime` `2026-03-29T17:00:00+01:00` and `endTime` `2026-03-29T18:00:00+01:00`
