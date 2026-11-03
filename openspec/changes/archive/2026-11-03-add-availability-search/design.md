## Context

- Booking already exists (change `add-appointment-booking`). Its rules live in the pure
  `scheduling` package:
  - `BusinessDay` (opening hours, the local-clock grid, the local day);
  - `StartTimeRule` (in the past, outside opening hours, off the grid);
  - `Allocator`, which finds the busy bays and technicians for a range and picks one free pair.
- `BookingAttempt` reads three things inside the dealership-day lock: the active bays
  (`ServiceBayRepository.findByDealershipIdAndActiveTrueOrderById`), the active qualified
  technicians (`TechnicianRepository.findActiveQualified`) and the day's confirmed appointments
  (`AppointmentRepository.findConfirmedStartingBetween`, served by
  `appointment_dealership_start_idx`).
- The error pipeline already turns a missing, mistyped or constraint-violating query parameter into
  `400 VALIDATION_FAILED` with an `errors` entry named after the parameter. `DEALERSHIP_NOT_FOUND`
  and `SERVICE_TYPE_NOT_FOUND` already exist.
- Catalog rows (dealerships, bays, technicians, qualifications) change only through migrations.
  Appointments change at runtime.
- Tests: `DatabaseIntegrationTest` resets `MutableClock` to `2026-11-02T00:00:00Z` before each test.
  `BookingConcurrencyIntegrationTest` already shows how to hold a dealership-day lock from a second
  connection.

## Goals / Non-Goals

**Goals:**
- Implement `specs/availability-search`. A listed slot must be exactly a time that booking would
  accept for an unbooked vehicle at that moment.
- Make that equivalence hold by construction (shared domain code) and prove it by tests (a
  property test in the domain, a round-trip test over HTTP).
- Never slow down, block or fail a booking because of a search.

**Non-Goals:** filtering by vehicle, several days per request, holding slots, caching, a dedicated
metric, shifts and holidays (see the proposal).

## Decisions

### 1. Packages

```
scheduling/                       pure domain (no Spring, no JPA)
  FreeResources        NEW        during(wanted, bayIds, technicianIds, commitments) -> free bay and technician ids
  Allocator            CHANGED    uses FreeResources, then picks the lowest bay and the lightest technician
  SlotFinder           NEW        find(day, duration, step, now, bayIds, technicianIds, dayCommitments) -> List<Slot>
  Slot                 NEW        (TimeRange range, int availableBays, int availableTechnicians)

availability/
  controller/AvailabilityController   GET /api/v1/dealerships/{dealershipId}/availability
  service/AvailabilityService         lookups, short-circuit for past days, repository reads, SlotFinder
  dto/response/AvailabilityResponse   dealershipId, serviceTypeId, date, timeZone, durationMinutes, slots
  dto/response/SlotResponse           startTime, endTime (OffsetDateTime), availableBays, availableTechnicians
```

The URL is nested under `/dealerships`, but the code sits in its own `availability` feature package,
as `AGENTS.md` prescribes. The catalog controller stays read-only catalog.

### 2. One definition of "free", shared by booking and search

`Allocator.allocate` today works out the busy bays and technicians and the booked minutes in one
loop. The busy-set part moves into `FreeResources.during(...)`: it returns the bay ids and the
technician ids with no `Commitment` overlapping the wanted range (half-open). `Allocator` then takes
the lowest free bay id and the free technician with the fewest booked minutes. Its signature and
behaviour do not change, so `BookingAttempt` is untouched and `AllocatorTest` guards the refactor.
`SlotFinder` takes the sizes of the same two lists as `availableBays` and `availableTechnicians`.

*Alternative: call `Allocator.allocate` once per candidate.* That answers only "is there a pair",
not the counts the spec needs, and it recomputes the booked minutes for nothing. *Alternative:
duplicate the overlap check in `SlotFinder`.* That gives two definitions of "free" that can drift
apart. Rejected.

### 3. Candidates on the local clock, filtered by booking's own rule

```
opens 08:00                                                               closes 18:00
  |------------------------------------------------------------------------|
  k = 0, 1, 2 ...   local = opens + k*step   while local < closes
     |
     +- start = ZonedDateTime.of(date, local, zone).toInstant()
     +- range = TimeRange.of(start, duration)
     +- StartTimeRule.check(range, now, day, step) present? -> skip   (past / ends after closing)
     +- free = FreeResources.during(range, ...)  no bay or no technician? -> skip
     +- Slot(range, free.bays.size, free.technicians.size)
  distinct by start instant, ascending
```

- The grid is counted on the local clock, the way `BusinessDay.isOnGrid` checks it. A step is never
  added to an `Instant`, because that would drift off the grid across a DST change.
- `StartTimeRule` is the same check booking runs, including "a start equal to now is in the past".
  So the search cannot list a time that booking would answer with `422`.
- The loop counts minutes from `opens` (not `LocalTime.plus`, which wraps at midnight) and stops at
  `closes`.
- In a DST gap, `ZonedDateTime.of` moves a nonexistent local time forward onto an existing one. The
  `distinct` keeps that from producing a duplicate. Opening hours never cover a gap in the seeded
  zones; see Risks.

Cost: at most `(closes - opens) / step` candidates (20 for 08:00-18:00 and 30 minutes), each
scanning the day's commitments. That is a few thousand comparisons in memory.

### 4. Service flow and queries

```
GET /api/v1/dealerships/{dealershipId}/availability?serviceTypeId=&date=
 +- controller: @Positive dealershipId and serviceTypeId, @DateTimeFormat(iso = DATE) date
 |               -> 400 VALIDATION_FAILED (errors: dealershipId | serviceTypeId | date)
 +- AvailabilityService, @Transactional(readOnly = true), READ COMMITTED, no advisory lock
     (1) dealership           -> 404 DEALERSHIP_NOT_FOUND
     (2) service type         -> 404 SERVICE_TYPE_NOT_FOUND
     (3) day = BusinessDay(date, zone, opens, closes)
         closing instant <= now -> 200, slots []      (no further queries)
     (4) active bays                     \
     (5) active qualified technicians     >  the same three repository calls as BookingAttempt
     (6) confirmed appointments that day /
     (7) SlotFinder.find(...) -> AvailabilityResponse.of(...)
```

- `@DateTimeFormat(iso = DATE)` is required. Without it, Spring parses a `LocalDate` parameter in
  the locale's short style, and `2026-11-03` would be rejected.
- Step (3) answers a past date, or today after closing, without reading appointments. That covers
  the "empty slots" scenarios with one cheap comparison.
- The repository calls are made here directly, not through a loader shared with booking (the
  user's choice during explore). Booking reads inside its lock and search reads outside it, so the
  call sites differ anyway. Each call site gets a one-line comment pointing to the other. The
  round-trip test (decision 7) fails if the two ever read different data.
- Times leave the service as `Instant`s and are converted in `SlotResponse.of` with
  `atZone(zone).toOffsetDateTime()`, the same conversion `AppointmentResponse` uses. The two
  endpoints therefore print identical strings for the same instant.

### 5. Correctness under concurrent requests

- **The search never blocks a booking, and no booking blocks the search.** It takes no advisory
  lock and sets no `lock_timeout`. Under MVCC, a plain `SELECT` waits neither for the dealership-day
  lock nor for an uncommitted inserted row. A booking in progress is simply not visible yet. The
  search therefore can never answer `SLOT_CONTENTION`. A test holds the lock from a second
  connection and expects `200` well within `scheduler.booking-lock-timeout`.
- **A consistent view without `REPEATABLE READ`.** All the appointments come from one statement
  (6), whose snapshot is a state the database really had. The other reads are catalog rows, which
  change only through migrations. A booking that commits between statements therefore cannot mix
  two states into one answer. `REPEATABLE READ` would add nothing. It was considered and dropped.
- **Search then book is a race, by design.** Two customers can see the same last slot. Booking
  re-checks everything under the dealership-day lock, and the exclusion constraints remain the
  guarantee. The loser gets `409 NO_AVAILABILITY`. The search never writes, so it cannot cause a
  double booking.
- **Why not queue behind bookings.** Taking the lock, even `pg_advisory_xact_lock_shared`, would
  make searches wait for in-flight bookings and fail with a timeout under a burst, exactly when
  customers need an answer. The gain would be seeing a booking a few milliseconds earlier, and the
  search-then-book race would still remain.
- **Connection pool.** A search holds one of the 5 Hikari connections for 5 short, index-served
  queries. `open-in-view` is off, so the connection returns to the pool when the service method
  ends.

### 6. Metrics and log events

- No new metric. Searches appear in `http_server_requests_seconds{method="GET",
  uri="/api/v1/dealerships/{dealershipId}/availability", status}`, with the histogram buckets
  already enabled. `scheduler_bookings_total` and `scheduler_booking_conflicts_total` are
  unaffected, because a search is not a booking.
- No new log event. Each search is already one access-log line, with the request id, status and
  duration. 404s go through the existing WARN problem log. A per-search INFO line would only repeat
  the access log on the busiest read endpoint.

### 7. Where each rule is tested, and how the equivalence is proven

Each rule is tested at the cheapest level where it lives. The slot rules (grid, opening hours,
"now", DST, counting, half-open overlap) live in `SlotFinder`, so their scenarios are unit tests:
milliseconds, no Docker. Integration tests check only what the domain cannot see:
- the HTTP shape and offsets;
- the repository queries;
- the dealership's calendar in the service;
- validation;
- the absence of a lock;
- agreement with booking.

Each scenario of the delta spec maps to exactly one test. Repeating a domain rule over HTTP would
not catch any additional fault. The first draft of the tasks did that: about 45 tests, about 27 of
them needing Docker. This plan has about 25, of which about 13 need Docker.

| Test | Spec scenarios |
| --- | --- |
| `SlotFinderTest` (unit) | Slots respect opening hours; Service longer than the step; Same-day query; Query at an exact slot time; Today after the last start; Fully booked time is hidden; Remaining capacity is reported; Bays free but no qualified technician free; Back-to-back start is listed; Daylight saving day (checked on instants; the offset formatting is the conversion that "Times in the dealership's offset" checks) |
| `AvailabilityIntegrationTest` | Response describes the context; Times in the dealership's offset; Unqualified, inactive and foreign resources are not counted; Cancelled appointments do not occupy resources; Date in the past; Today is the dealership's date; the six validation scenarios (the three `400`s as one parameterized test) |
| `BookingConcurrencyIntegrationTest` | Search during a booking in progress |
| `AvailabilityBookingIntegrationTest` | A booking removes the slots it overlaps; Listed slot taken by another customer |
| existing booking tests | Listed slot but the vehicle is busy (the `VEHICLE_ALREADY_BOOKED` scenarios of `appointment-booking` prove that refusal; a listed slot changes nothing about it) |

Two tests prove that search and booking agree. Neither may be cut, because the repository calls
are not shared:

- **Domain property test** (`SlotFinderTest`, no Spring): a seeded `Random` generates a few hundred
  cases (0-3 bays, 0-3 technicians, random commitments on and off the grid, random "now"). For every
  grid start of the day, a slot is listed exactly when `StartTimeRule.check` passes and
  `Allocator.allocate` returns a pair. The counts must equal a brute-force overlap count written
  independently in the test. A failing seed is printed, so it can be replayed.
- **Round-trip integration test** over HTTP, on a workshop with existing appointments:
  - every listed slot, booked on its own, is `201` (each booking is cancelled before the next);
  - at one slot, exactly `min(availableBays, availableTechnicians)` bookings with different
    vehicles succeed and the next gets `409 NO_AVAILABILITY`;
  - every future grid start inside opening hours that is not listed is refused with
    `409 NO_AVAILABILITY`.

`FreeResources` gets no tests of its own. `AllocatorTest`, unchanged, and the property test cover
it. Only "Today is the dealership's date" moves `MutableClock`, to `2026-11-02T20:00:00Z`. The DST
scenario needs no clock change, because `SlotFinder` takes `now` as a parameter.

## Risks / Trade-offs

- [Listed slot taken before the client books] -> Expected and specified. Booking answers
  `409 NO_AVAILABILITY` and the client searches again. Holding slots is a non-goal.
- [Vehicle conflicts are invisible to the search] -> Specified (`VEHICLE_ALREADY_BOOKED`) and named
  in the spec's purpose. A `vehicleId` filter can be added later without breaking clients.
- [Booking and search read through separate repository calls and could drift] -> Cross-reference
  comments plus the round-trip test.
- [Opening hours covering a DST transition] -> In a gap, the shifted candidate is deduplicated. In a
  fall-back overlap, `ZonedDateTime.of` picks the earlier offset, so the second occurrence of a
  local time is not offered, although booking would accept it. No seeded dealership opens across a
  transition (they change at night), so this is left as is and documented.
- [Search traffic competes with bookings for 5 connections] -> Short, index-served queries, with no
  waiting on locks. Search-specific caching is a non-goal.
- [A `date` with an expanded year such as `+300000-01-01` passes ISO parsing but is beyond
  PostgreSQL's timestamp range, giving `500`] -> Booking has the same gap for `startTime`. See Open
  Questions. Past dates of any year are safe, because step (3) answers them without a query.

## Migration Plan

Additive only: no schema change, no new configuration, no change to existing responses. Deploying
adds the endpoint. Rolling back removes it, and nothing persisted depends on it.

## Open Questions

- Should both endpoints reject years beyond 9999 with `400 VALIDATION_FAILED`, so that an absurd
  date can never reach the database? This touches booking as well, so it is left to a separate
  change rather than fixed only here.
