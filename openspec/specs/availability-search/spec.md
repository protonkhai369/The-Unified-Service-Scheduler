# Availability Search Specification

## Purpose
Show customers and service advisors, before they book, which start times on a given day can
actually be served: times when a service bay and a qualified technician are both free for the
whole service duration. Uses exactly the same rules and data as booking, so a listed slot is
bookable unless another request takes it first.

## Requirements

### Requirement: List bookable start times for a day
The system SHALL expose `GET /api/v1/dealerships/{dealershipId}/availability?serviceTypeId=&date=`
where `date` is an ISO local date at the dealership. It SHALL return candidate start times spaced
by `scheduler.slot-step` (default 30 minutes) from opening time, including only starts whose
service finishes by closing time. Times MUST be expressed in the dealership's time zone.

#### Scenario: Slots respect opening hours
- **WHEN** a 60-minute service is queried for a dealership open 08:00-18:00 with no bookings
- **THEN** slots start at 08:00, 08:30, ... and the last slot starts at 17:00

#### Scenario: Response describes the context
- **WHEN** availability is requested
- **THEN** the response includes `dealershipId`, `serviceTypeId`, `date`, `timeZone`, `durationMinutes` and `slots`

### Requirement: Slots require a free bay and a free qualified technician
A start time SHALL be listed only if at least one active bay and one active technician qualified
for the service type are free for the entire service duration. Each slot MUST report how many
bays (`availableBays`) and qualified technicians (`availableTechnicians`) remain free.

#### Scenario: Fully booked time is hidden
- **WHEN** every bay is booked from 10:00 to 11:00
- **THEN** no slot whose service would overlap 10:00-11:00 is listed

#### Scenario: Remaining capacity is reported
- **WHEN** one of two bays and one of three qualified technicians are booked at a slot
- **THEN** that slot reports `availableBays` 1 and `availableTechnicians` 2

### Requirement: No past start times
The system MUST NOT list start times earlier than the current time.

#### Scenario: Same-day query
- **WHEN** availability for today is requested at 08:45 local time with a 30-minute step
- **THEN** the first listed slot starts at 09:00 or later

### Requirement: Availability query validation
The system SHALL respond `404` for an unknown dealership or service type and `400` for a missing
or malformed `serviceTypeId` or `date`.

#### Scenario: Unknown service type
- **WHEN** `serviceTypeId` does not exist
- **THEN** the response is `404` with code `SERVICE_TYPE_NOT_FOUND`

#### Scenario: Malformed date
- **WHEN** `date` is not an ISO date such as `2026-11-02`
- **THEN** the response is `400`
