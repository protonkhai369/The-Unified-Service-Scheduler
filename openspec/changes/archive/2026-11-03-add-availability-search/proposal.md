## Why

Today a client can only learn whether a time is free by booking it: it guesses a start time,
`POST`s it, and gets `409 NO_AVAILABILITY` when the guess was wrong. The brief's core requirement 2
(a bay and a qualified technician free for the whole duration) is checked only at the moment of
booking. The `availability-search` spec already describes the endpoint that shows bookable times up
front, but nothing implements it. It is the only specified capability that is still missing.

Exploring the spec against the booking code also showed that it does not yet follow booking's rules
exactly, even though its purpose says it does. A slot starting exactly at the current instant would
be listed and then rejected as `START_TIME_IN_PAST`. Slot times have no defined format. There is no
time-zone scenario. Searching a date in the past has no defined answer. This change fixes the spec
and builds the endpoint on the same scheduling domain that booking uses. Customers then pick a time
that works instead of retrying (maintainability, fewer wasted requests).

## What Changes

- `GET /api/v1/dealerships/{dealershipId}/availability?serviceTypeId=&date=` returns the bookable
  start times of one dealership-local day for one service type:
  - `dealershipId`, `serviceTypeId`, `date`, `timeZone`, `durationMinutes` and `slots`;
  - each slot has `startTime` and `endTime` with the dealership's offset at that instant, in the same
    form as an appointment, so a client can send `startTime` straight to `POST /api/v1/appointments`.
    Each slot also has `availableBays` and `availableTechnicians`;
  - slots are ordered by start time. Only start times that booking would accept are listed: after
    the current instant, inside opening hours and on the `scheduler.slot-step` grid. At least one
    active bay and one active qualified technician must also be free for the whole duration.
- A date in the past, or today after the last bookable start, returns `200` with `"slots": []`, not
  an error.
- Validation reuses existing codes: `400 VALIDATION_FAILED` for a missing, malformed or non-positive
  parameter; then `404 DEALERSHIP_NOT_FOUND`; then `404 SERVICE_TYPE_NOT_FOUND`.
- The search only reads. It never takes the booking lock and never writes.
- The scheduling domain gets one shared definition of "which bays and technicians are free for a
  period". The allocator uses it to pick a pair, and the new slot finder uses it to count. Booking
  behaviour does not change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `availability-search`: slots carry `startTime` and `endTime` with the dealership's offset and are
  ordered by start time. "No past start times" becomes "only start times after the current
  instant", the same rule as booking, with scenarios for the exact current instant, a past date and
  today after closing. Adds a daylight-saving-time scenario, the order of the two `404`s, the
  `400` for a non-positive `serviceTypeId`, and an unknown-dealership scenario. A new requirement
  states that a search reflects committed bookings, never waits for or blocks a booking, and
  reserves nothing.

## Non-goals

- Filtering by vehicle (`vehicleId`). A listed slot can still be refused with
  `409 VEHICLE_ALREADY_BOOKED` when the vehicle has another appointment at that time. The spec's
  purpose will say so.
- Ranges of several days, or "next available slot" suggestions.
- Holding or reserving a slot between search and booking. The booking path stays the only guarantee.
- Listing fully booked times with zero counts, caching results, or pagination.
- A dedicated availability metric. `http_server_requests_seconds` already tags requests by URI
  template.
- Technician shifts, holidays and per-day opening hours (as for booking).

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- `date` is a calendar date at the dealership, not at the client.
- A slot that is listed is not reserved. Another booking may take it first (`409 NO_AVAILABILITY`),
  or the vehicle may already be booked then (`409 VEHICLE_ALREADY_BOOKED`).
- `availableBays` and `availableTechnicians` are counted independently. The number of bookings that
  still fit at that start time is the smaller of the two.
- There is no booking horizon. Any future date can be searched, as any future time can be booked.

## Impact

- New code:
  - `availability`: controller, service and response records.
  - `scheduling`: a pure slot finder, and the free-resource computation extracted from `Allocator`.
- Changed:
  - `Allocator` is refactored to use the shared free-resource computation; its tests stay green.
  - Docs: `docs/SYSTEM_DESIGN.md` (availability section, API table, assumptions), `docs/AI_LOG.md`,
    and the "current state" lines in `AGENTS.md` and `openspec/config.yaml`.
- APIs: one new read-only endpoint. Existing endpoints are unchanged.
- No schema change, no new `ErrorCode`, no new dependency, no new configuration property
  (`scheduler.slot-step` already exists).
