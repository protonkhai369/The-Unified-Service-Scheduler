## ADDED Requirements

### Requirement: Sign-in and session
The web client SHALL let a person sign in with e-mail and password through Supabase Auth, and SHALL
send the resulting access token as a bearer token on every API call that needs one. A signed-out
visitor opening any page other than sign-in SHALL be taken to the sign-in page. When an API call
answers `401`, the client SHALL end the session and return to sign-in with a message that the
session expired. The person SHALL be able to sign out from every page.

#### Scenario: Successful sign-in
- **WHEN** a customer enters valid credentials and submits
- **THEN** they land on their role's start page, and later API calls carry `Authorization: Bearer <token>`

#### Scenario: Wrong credentials
- **WHEN** the credentials are rejected by Supabase Auth
- **THEN** the sign-in page stays, shows "E-mail or password is incorrect" next to the form, and keeps the e-mail entered

#### Scenario: Signed-out visitor
- **WHEN** a signed-out visitor opens `/appointments`
- **THEN** they are taken to the sign-in page

#### Scenario: Expired session
- **WHEN** any API call answers `401`
- **THEN** the client signs out and shows the sign-in page with "Your session has expired. Please sign in again."

### Requirement: Role-based start page and navigation
After sign-in, the client SHALL read `GET /api/v1/me`. Navigation and the start page SHALL depend on
the role:
- a customer gets *Book a service* (start) and *My appointments*;
- an advisor gets *Day schedule* (start) and *Book for a customer*;
- an admin gets the same as an advisor, plus a dealership picker on the schedule.

An account without a usable role SHALL see a page explaining that it is not linked to a customer or
dealership, and no booking functions. The navigation SHALL mark the current page.

#### Scenario: Customer start page
- **WHEN** a customer signs in
- **THEN** *Book a service* is shown and the navigation offers *Book a service* and *My appointments*

#### Scenario: Advisor start page
- **WHEN** an advisor of Saigon Auto Centre signs in
- **THEN** that dealership's day schedule for today is shown, with *Day schedule* and *Book for a customer* in the navigation

#### Scenario: Account without a role
- **WHEN** `GET /api/v1/me` returns `role` `null`
- **THEN** the page explains that the account is not linked yet, and no booking screen is reachable

### Requirement: Booking a service
*Book a service* SHALL take the person, in order, through:
1. the vehicle: for a customer, their own vehicles from `GET /api/v1/me/vehicles`;
2. the service type;
3. the dealership and the date;
4. a free start time from the availability search;
5. a confirmation step showing everything chosen.

Times SHALL be shown in the dealership's local time and labelled with its time zone. Confirming
SHALL call `POST /api/v1/appointments` with an `Idempotency-Key` created once for that confirmation
step. Any retry of the same confirmation, including a double click, SHALL reuse that key. Success
SHALL show the booked appointment with its bay and technician.

#### Scenario: Booking happy path
- **WHEN** a customer picks their Golf, *Oil and filter change*, Saigon Auto Centre, 3 November and the 10:00 slot, and confirms
- **THEN** the booking is sent once with the chosen ids and `startTime` `2026-11-03T10:00:00+07:00`, and the confirmation shows the bay, the technician and "10:00–10:45 (Asia/Ho_Chi_Minh)"

#### Scenario: Times in the dealership's zone
- **WHEN** the browser runs in UTC and the availability for Saigon lists a slot starting `2026-11-03T10:00:00+07:00`
- **THEN** the slot is labelled 10:00, not 03:00, and the page names the dealership's time zone

#### Scenario: No free slot that day
- **WHEN** the availability search returns no slots
- **THEN** the page says that no time is free that day and offers to pick another date

#### Scenario: Double click books once
- **WHEN** the confirm button is pressed twice quickly
- **THEN** the button is disabled while the request runs, and at most one booking request is sent

#### Scenario: Retry after a network failure
- **WHEN** the first confirm request fails without a response and the person presses *Try again*
- **THEN** the retry carries the same `Idempotency-Key` as the first attempt

#### Scenario: Slot taken meanwhile
- **WHEN** confirming answers `409` with code `NO_AVAILABILITY`
- **THEN** the client says that the time was just taken, returns to the slot step, and reloads the slots

#### Scenario: Vehicle already booked
- **WHEN** confirming answers `409` with code `VEHICLE_ALREADY_BOOKED`
- **THEN** the client explains that the vehicle already has an appointment at that time

### Requirement: Choosing a service type
The service step SHALL show every service type with its name and duration as selectable options. When
there are more than six, a filter field SHALL narrow them as the person types, ignoring letter case
and accents, and matching the name or the code.

#### Scenario: Few services as options
- **WHEN** the catalog has four service types
- **THEN** all four are shown as options with their durations, and no filter field is shown

#### Scenario: Filtering ignores accents
- **WHEN** the catalog has more than six service types, one named "Kiểm tra phanh", and the person types `kiem tra`
- **THEN** only the matching service types remain visible

### Requirement: My appointments
*My appointments* SHALL list a customer's appointments from `GET /api/v1/me/appointments`, split into
*Upcoming* (confirmed, not yet started) and *Past or cancelled*. Each entry shows the date, local time
with time zone, dealership, service and status. An upcoming appointment SHALL offer *Cancel*, which
asks for confirmation before calling the cancel endpoint and then shows the appointment as cancelled.

#### Scenario: Upcoming and past
- **WHEN** a customer has one confirmed future appointment and one cancelled appointment
- **THEN** the first is under *Upcoming* with a *Cancel* action, and the second is under *Past or cancelled* without it

#### Scenario: Cancel with confirmation
- **WHEN** the customer chooses *Cancel* and confirms
- **THEN** the cancel endpoint is called once and the appointment moves to *Past or cancelled* with status cancelled

#### Scenario: Too late to cancel
- **WHEN** cancelling answers `409` with code `APPOINTMENT_ALREADY_STARTED`
- **THEN** the client explains that an appointment that has started cannot be cancelled

### Requirement: Day schedule
*Day schedule* SHALL show one dealership's confirmed appointments for one local date, from
`GET /api/v1/dealerships/{id}/appointments?date=`, as a timeline with one row per service bay across
the opening hours. Each appointment SHALL show its local start and end, technician and service. The
date SHALL be changeable (previous day, next day, today). An advisor's dealership is fixed. An admin
SHALL pick the dealership.

#### Scenario: Advisor sees the day
- **WHEN** an advisor of Saigon opens the schedule for 3 November, with appointments on Bay A at 08:00 and on Bay B at 10:00
- **THEN** both appear in their bay rows at their local times, with technician and service

#### Scenario: Empty day
- **WHEN** the dealership has no appointment that day
- **THEN** the bays are shown with an empty-state message instead of blocks

#### Scenario: Admin picks a dealership
- **WHEN** an admin opens the schedule
- **THEN** a dealership picker is shown, and changing it reloads the schedule for that dealership

### Requirement: Booking for a customer
*Book for a customer* SHALL let an advisor or admin find a customer with `GET /api/v1/customers?q=`.
The search runs once at least two characters are typed, and waits until typing pauses. The results
show each customer's name, e-mail and vehicles. Choosing a customer and one of their vehicles SHALL
continue with the booking flow from the service step, for that customer.

#### Scenario: Find and book for a customer
- **WHEN** an advisor types `duc`, chooses "Lê Minh Đức" and their Focus, and completes the booking
- **THEN** the booking is sent with that customer's and vehicle's ids

#### Scenario: Too short to search
- **WHEN** one character is typed
- **THEN** no search request is sent, and a hint asks for at least two characters

#### Scenario: Nobody found
- **WHEN** the search returns no customers
- **THEN** the client says that no customer matches

### Requirement: Accessible, minimalist presentation
The client SHALL use the navy, ocean blue, yellow, black and white palette. Text and interactive
elements SHALL meet WCAG 2.2 AA contrast, and yellow SHALL never be used as text or as a border on
white. Each screen SHALL have at most one primary (yellow) action. Every control SHALL be operable by
keyboard with a visible focus indicator. Every form field SHALL have a visible label. Errors SHALL be
shown next to the field or action they concern and announced to screen readers. The layout SHALL work
from 375 px wide without horizontal scrolling. Animations SHALL be turned off when the person prefers
reduced motion.

#### Scenario: Keyboard-only booking
- **WHEN** a person completes the booking flow using only the keyboard
- **THEN** every option and button is reachable with Tab and operable with Enter or Space, with a visible focus ring

#### Scenario: Errors are announced
- **WHEN** an API error is shown
- **THEN** it is in an element with `role="alert"`, and it names what happened in plain language rather than the raw error code

#### Scenario: Unknown error
- **WHEN** an API call fails with a code the client does not know, or a `500`
- **THEN** a generic message is shown together with the request id from the response, for support
