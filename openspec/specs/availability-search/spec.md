# Availability Search Specification

## Purpose
Show customers and service advisors, before they book, which start times on a given day can
actually be served: times when a service bay and a qualified technician are both free for the
whole service duration. Uses exactly the same rules and data as booking, so a listed slot is
bookable unless another request takes it first or the vehicle already has an appointment then.

## Requirements

### Requirement: List bookable start times for a day
The system SHALL expose `GET /api/v1/dealerships/{dealershipId}/availability?serviceTypeId=&date=`
where `date` is an ISO local date at the dealership. It SHALL return candidate start times spaced
by `scheduler.slot-step` (default 30 minutes) on the dealership's local clock from opening time,
including only starts whose service finishes by closing time, ordered by start time. Each slot
SHALL carry a `startTime` and an `endTime` equal to the start plus the service type's duration. Both
MUST be expressed with the dealership's UTC offset at that instant, in the same format as an
appointment's times, so that a slot's `startTime` can be sent unchanged as the `startTime` of a
booking request.

#### Scenario: Slots respect opening hours
- **WHEN** a 60-minute service is queried for a dealership open 08:00-18:00 with no bookings
- **THEN** slots start at 08:00, 08:30, ... and the last slot starts at 17:00 and ends at 18:00

#### Scenario: Service longer than the step
- **WHEN** a 90-minute service is queried for a dealership open 08:00-18:00 with no bookings
- **THEN** the last slot starts at 16:30, because a start at 17:00 would end after closing time

#### Scenario: Response describes the context
- **WHEN** availability is requested
- **THEN** the response includes `dealershipId`, `serviceTypeId`, `date`, `timeZone`, `durationMinutes` and `slots`
- **AND** each slot includes `startTime`, `endTime`, `availableBays` and `availableTechnicians`

#### Scenario: Times in the dealership's offset
- **WHEN** a 60-minute service is queried for an `Asia/Ho_Chi_Minh` dealership on `2026-11-03`
- **THEN** the slot starting at 10:00 local time has `startTime` `2026-11-03T10:00:00+07:00` and `endTime` `2026-11-03T11:00:00+07:00`
- **AND** a booking request with that `startTime` for a free vehicle is confirmed

#### Scenario: Daylight saving day
- **WHEN** a 60-minute service is queried for a `Europe/London` dealership open 08:00-18:00 on `2026-03-29`, the day British Summer Time starts
- **THEN** the first slot has `startTime` `2026-03-29T08:00:00+01:00`
- **AND** the last slot has `startTime` `2026-03-29T17:00:00+01:00` and `endTime` `2026-03-29T18:00:00+01:00`

### Requirement: Slots require a free bay and a free qualified technician
A start time SHALL be listed only if at least one active bay and one active technician qualified
for the service type are free for the entire service duration. Each slot MUST report how many
bays (`availableBays`) and qualified technicians (`availableTechnicians`) remain free. Resources are
counted exactly as booking allocates them: only the dealership's own active bays and its active
technicians with an active qualification for the service type count, only `CONFIRMED` appointments
occupy them, and periods are half-open, so a service may start when another one ends.

#### Scenario: Fully booked time is hidden
- **WHEN** every bay is booked from 10:00 to 11:00
- **THEN** no slot whose service would overlap 10:00-11:00 is listed

#### Scenario: Remaining capacity is reported
- **WHEN** one of two bays and one of three qualified technicians are booked at a slot
- **THEN** that slot reports `availableBays` 1 and `availableTechnicians` 2

#### Scenario: Bays free but no qualified technician free
- **WHEN** bays are free from 10:00 to 11:00 but every technician qualified for the service type is busy then
- **THEN** no slot whose service would overlap 10:00-11:00 is listed

#### Scenario: Back-to-back start is listed
- **WHEN** the only bay and the only qualified technician are booked from 09:00 to 10:00 and a 60-minute service is queried
- **THEN** the slot starting at 10:00 is listed and the slot starting at 09:30 is not

#### Scenario: Unqualified, inactive and foreign resources are not counted
- **WHEN** a dealership has an inactive bay, an inactive technician, a technician without the qualification, and a technician whose qualification is deactivated, and another dealership has free resources
- **THEN** none of them is counted in `availableBays` or `availableTechnicians`

#### Scenario: Cancelled appointments do not occupy resources
- **WHEN** the only bay and technician had an appointment from 10:00 to 11:00 that was cancelled
- **THEN** the slot starting at 10:00 is listed with `availableBays` 1 and `availableTechnicians` 1

### Requirement: No past start times
The system MUST list only start times after the current instant. A start time equal to the current
instant is not listed, because booking rejects it as in the past. A date earlier than the
dealership's current local date, or the current date once no start time is left, SHALL return
`200` with an empty `slots` array.

#### Scenario: Same-day query
- **WHEN** availability for today is requested at 08:45 local time with a 30-minute step
- **THEN** the first listed slot starts at 09:00 or later

#### Scenario: Query at an exact slot time
- **WHEN** availability for today is requested at exactly 09:00:00 local time with a 30-minute step
- **THEN** the slot starting at 09:00 is not listed and the first listed slot starts at 09:30

#### Scenario: Date in the past
- **WHEN** availability is requested for yesterday's date at the dealership
- **THEN** the response is `200` with an empty `slots` array

#### Scenario: Today after the last start
- **WHEN** availability for a 60-minute service is requested today at 17:30 local time at a dealership that closes at 18:00
- **THEN** the response is `200` with an empty `slots` array

#### Scenario: Today is the dealership's date
- **WHEN** the current instant is `2026-11-02T20:00:00Z`, which is 03:00 on `2026-11-03` at an `Asia/Ho_Chi_Minh` dealership open from 08:00
- **THEN** availability for `2026-11-03` lists a first slot with `startTime` `2026-11-03T08:00:00+07:00`
- **AND** availability for `2026-11-02` returns an empty `slots` array

### Requirement: Availability query validation
The system SHALL respond `400` with code `VALIDATION_FAILED` and an `errors` entry naming the
parameter for a missing, malformed or non-positive `serviceTypeId`, a missing or malformed `date`,
or a non-positive dealership id. It SHALL respond `404` with code `DEALERSHIP_NOT_FOUND` for an
unknown dealership and `404` with code `SERVICE_TYPE_NOT_FOUND` for an unknown service type; when
both are unknown, the dealership is reported.

#### Scenario: Unknown dealership
- **WHEN** the dealership id does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Unknown service type
- **WHEN** `serviceTypeId` does not exist
- **THEN** the response is `404` with code `SERVICE_TYPE_NOT_FOUND`

#### Scenario: Unknown dealership and service type
- **WHEN** neither the dealership id nor `serviceTypeId` exists
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Missing service type
- **WHEN** `serviceTypeId` is omitted
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `serviceTypeId`

#### Scenario: Non-positive service type
- **WHEN** `serviceTypeId` is `0`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `serviceTypeId`

#### Scenario: Malformed date
- **WHEN** `date` is not an ISO date such as `2026-11-02`, for example `02/11/2026`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `date`

### Requirement: Search reads bookings without blocking them
A search SHALL reflect every booking and cancellation committed before it starts. It MUST NOT wait
for bookings in progress, MUST NOT respond `SLOT_CONTENTION`, and MUST NOT change any appointment.
A listed slot is not reserved: a later booking of it can still be refused with `NO_AVAILABILITY`
when others have taken the capacity, or with `VEHICLE_ALREADY_BOOKED` when the vehicle already has
an appointment then.

#### Scenario: A booking removes the slots it overlaps
- **WHEN** a dealership has one bay and one qualified technician, a 60-minute service is booked at 10:00, and availability for that service is then requested
- **THEN** the slots starting at 09:30, 10:00 and 10:30 are not listed
- **AND** the slots starting at 09:00 and 11:00 are listed

#### Scenario: Search during a booking in progress
- **WHEN** a booking for the same dealership and local day is holding its turn and has not committed
- **THEN** availability responds `200` without waiting for that booking to finish

#### Scenario: Listed slot taken by another customer
- **WHEN** a slot is listed with one bay and one technician free, and another customer books it first
- **THEN** a booking of that slot responds `409` with code `NO_AVAILABILITY`

#### Scenario: Listed slot but the vehicle is busy
- **WHEN** a slot is listed and the vehicle to be booked already has a confirmed appointment overlapping it at another dealership
- **THEN** a booking of that slot for that vehicle responds `409` with code `VEHICLE_ALREADY_BOOKED`
