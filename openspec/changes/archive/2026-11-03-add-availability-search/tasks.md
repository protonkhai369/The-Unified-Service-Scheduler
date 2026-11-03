## 1. Scheduling domain (tests first)

- [x] 1.1 Extract `FreeResources` from `Allocator` and make `Allocator.allocate` use it without changing its signature or results. `AllocatorTest` passes unchanged and covers the refactor (no separate `FreeResources` tests, design decision 7). `BookingAttempt` is not edited
- [x] 1.2 Write example unit tests in `SlotFinderTest`, one per domain scenario of the delta spec (design decision 7 table):
  - "Slots respect opening hours": 19 slots, 08:00 to 17:00, ascending;
  - "Service longer than the step": the last slot is 16:30;
  - "Same-day query" (`now` 08:45 gives 09:00 first) and "Query at an exact slot time" (`now` 09:00 gives 09:30 first);
  - "Today after the last start": `now` 17:30 gives an empty list;
  - "Remaining capacity is reported", "Fully booked time is hidden", "Bays free but no qualified technician free", "Back-to-back start is listed";
  - "Daylight saving day": London on 2026-03-29 gives `07:00Z` to `16:00Z`;

  Also cover a zone whose DST gap falls inside opening hours: it yields no duplicate start (design decision 3)
- [x] 1.3 Write the property test in `SlotFinderTest`:
  - a seeded `Random` generates at least 300 cases: 0-3 bays, 0-3 technicians, random commitments on and off the grid, random `now`;
  - for every grid start of the day, a slot is listed exactly when `StartTimeRule.check` passes and `Allocator.allocate` returns a pair;
  - its counts equal a brute-force overlap count written independently in the test;
  - a failure message names the seed
- [x] 1.4 Implement `Slot` and `SlotFinder` in `scheduling` as in design decision 3 (local-clock candidates counted in minutes from `opens`, filtered by `StartTimeRule`, counted with `FreeResources`, distinct by start), with no Spring or JPA imports; make 1.2 and 1.3 pass

## 2. Availability endpoint (tests first)

- [x] 2.1 Write `AvailabilityIntegrationTest` for the wiring only; the slot rules are covered in group 1:
  - response shape and offset: "Response describes the context" and "Times in the dealership's offset", including a booking with the listed `startTime`;
  - resource queries: "Unqualified, inactive and foreign resources are not counted" and "Cancelled appointments do not occupy resources";
  - the dealership's calendar: "Today is the dealership's date" (moves `MutableClock` to `2026-11-02T20:00:00Z`) and "Date in the past";
  - the three `404` scenarios;
  - the three `400` scenarios as one parameterized test
- [x] 2.2 Add `AvailabilityResponse` and `SlotResponse` records in `availability.dto.response`, with static `of()` mappers that convert with `atZone(zone).toOffsetDateTime()`, as `AppointmentResponse` does
- [x] 2.3 Implement `AvailabilityService` as in design decision 4:
  - read-only transaction, no advisory lock;
  - dealership lookup, then service type;
  - an empty answer without further queries once the day's closing instant is not after `now`;
  - the three repository reads, each with a comment pointing to `BookingAttempt`, and the matching comment added in `BookingAttempt`;
  - `SlotFinder`
- [x] 2.4 Implement `AvailabilityController` at `/api/v1/dealerships/{dealershipId}/availability`, with `@Positive` ids, a required `@DateTimeFormat(iso = DATE)` `date`, OpenAPI examples, and no `@Validated`; make 2.1 pass

## 3. Concurrency and round trip (tests first)

- [x] 3.1 Add "Search during a booking in progress" to `BookingConcurrencyIntegrationTest`, where `BookingAttempt.lockKey` is visible:
  - hold the dealership-day lock from a second connection;
  - expect availability to answer `200` in well under the test `scheduler.booking-lock-timeout`, with the same slots as before the lock was taken
- [x] 3.2 Write `AvailabilityBookingIntegrationTest`:
  - the round-trip test from design decision 7, on a workshop with existing appointments: every listed slot, booked alone and then cancelled, is `201`; at one slot, exactly `min(availableBays, availableTechnicians)` bookings succeed and the next is `409 NO_AVAILABILITY`; every future, in-hours grid start that is not listed is refused with `409 NO_AVAILABILITY`;
  - on a workshop with one bay and one technician: "A booking removes the slots it overlaps" and "Listed slot taken by another customer"
- [x] 3.3 Make 3.1 and 3.2 pass without adding a lock, a `lock_timeout` or a write to the search path

## 4. OpenAPI, documentation and manual checks

- [x] 4.1 Extend `OpenApiIntegrationTest`: `/v3/api-docs` documents the availability path with a required `serviceTypeId` and a required `date` of format `date`. Then call the endpoint once from Swagger UI with "Try it out"
- [x] 4.2 Update `docs/SYSTEM_DESIGN.md`:
  - an Availability section with the flow, slot generation on the local clock, and why the search takes no lock and runs at `READ COMMITTED`;
  - an API table row and an example response;
  - the introduction line that promised this section, and the "will reuse it" line under Booking;
  - the proposal's assumptions in the Assumptions section
- [x] 4.3 Update the Purpose of `openspec/specs/availability-search/spec.md` (delta specs carry requirements only): a listed slot is bookable unless another request takes it first or the vehicle already has an appointment then
- [x] 4.4 Update the current-state lines in `AGENTS.md` and `openspec/config.yaml`: the availability search exists
- [x] 4.5 Run the application locally on the demo data and capture the cURL output of:
  - availability for a demo dealership and day;
  - a booking of its first slot;
  - availability again, showing the lower counts;
  - one `404` and one `400`.

  Then cancel the demo appointment
- [x] 4.6 Add the change to `docs/AI_LOG.md`:
  - the decisions taken with the user in explore: `OffsetDateTime` start and end, `200` with empty slots for past dates, vehicle conflicts named only in the purpose, separate repository calls;
  - the decisions taken by the AI: `FreeResources` extraction, `StartTimeRule` as the filter, `READ COMMITTED` instead of the `REPEATABLE READ` first suggested, no lock, no metric;
  - the inconsistency found in the original spec (a start equal to "now" was listed);
  - the test plan cut at the user's request from about 45 tests (27 integration) to about 25 (13 integration), each rule tested at the cheapest level where it lives;
  - the problems found while implementing;
  - the results of 4.5 and 5.x

## 5. Verification

- [x] 5.1 Run `./mvnw test` with `JAVA_HOME` pointing at JDK 21+ and quote the Surefire summary; every test passes
- [x] 5.2 Run the suite once with `-DargLine=-Duser.timezone=Asia/Kathmandu` and confirm it passes
- [x] 5.3 Check every scenario of `specs/availability-search` in this change against the design decision 7 table: each maps to one passing test, unit or integration. "Listed slot but the vehicle is busy" maps to the existing `VEHICLE_ALREADY_BOOKED` tests of booking. List any gap
- [x] 5.4 Run `openspec validate add-availability-search --strict` and confirm it passes
