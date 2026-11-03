## Context

- Appointment endpoints exist for booking, reading one appointment and cancelling it. Every one of
  them needs a Supabase token and applies `AccessPolicy`.
- A customer is linked to a customer record through `app_metadata.customer_id`, and an advisor to a
  dealership through `dealership_id` (`add-supabase-auth`).
- `AppointmentView` (an appointment plus its dealership's zone and its bay and technician names) is
  read with one JPQL query and mapped by `AppointmentResponse.of`.
- `BusinessDay.wholeDay()` gives the instants of a local day, daylight saving included. The
  availability search already reads "the appointments of one dealership-day" this way.
- `customer` and `vehicle` tables exist (`vehicle.customer_id`). No endpoint exposes them yet.

## Goals / Non-Goals

**Goals:**
- Let a client find out who is signed in, which vehicles they can book for, and which appointments
  they have, using only the token.
- Give advisors their dealership's day in one request.
- Keep every access rule in the pure `AccessPolicy`, unit-tested.

**Non-Goals:** customer search, enriched list items, pagination and filters, master-data editing,
the web client (see the proposal).

## Decisions

### 1. Two list endpoints, not one `GET /api/v1/appointments?dealershipId=&date=`

```
GET /api/v1/me/appointments                                   customer: "my appointments"
GET /api/v1/dealerships/{dealershipId}/appointments?date=     advisor/admin: the day schedule
```

- Each use case has its own audience and natural bound. A customer's history is small. A
  dealership-day is bounded by opening hours and bays. A single filtered endpoint would need
  role-dependent rules, such as "date required, except for customers", plus a silent scope that
  turns a mismatched `dealershipId` into an empty list.
- The schedule path mirrors `/dealerships/{dealershipId}/availability`. A client builds the day view
  from the two calls on one resource.
- Alternative kept for later: an admin-wide search with paging. No client needs it yet.

### 2. Where the code goes

```
account/                                  new feature: the caller
  controller/AccountController            GET /api/v1/me, GET /api/v1/me/vehicles
  service/AccountService                  resolves names; vehicles of the caller's customer
  dto/response/AccountResponse            userId, role, customer, dealership
  dto/response/VehicleResponse            id, vin, make, model, modelYear
appointment/
  controller/AppointmentListController    GET /api/v1/me/appointments,
                                          GET /api/v1/dealerships/{dealershipId}/appointments
  service/AppointmentService              + appointmentsOf(caller), dealershipDay(id, date),
                                          both @PreAuthorize
  repository/AppointmentRepository        + findViewsOfCustomer, findViewsStartingBetween
customer/repository/VehicleRepository     + findByCustomerIdOrderById
security/AccessPolicy                     + isLinkedCustomer(user), mayViewSchedule(user, dealershipId)
security/SecurityConfiguration            + @EnableMethodSecurity
web/error/GlobalExceptionHandler          + AccessDeniedException → 403 FORBIDDEN problem
```

- `/me` and `/me/vehicles` describe the caller, who may be staff, so they go in their own feature
  rather than in `customer`.
- `AppointmentController` keeps its `/api/v1/appointments` mapping. The lists live in a second
  controller because their paths start elsewhere.
- List items reuse `AppointmentResponse` and `ResourceRef`, so the shape cannot drift from
  `GET /api/v1/appointments/{id}`.

### 3. Access rules: `@PreAuthorize` where the caller and the arguments decide

```java
static boolean isLinkedCustomer(AuthenticatedUser user)                    // CUSTOMER with customer_id
static boolean mayViewSchedule(AuthenticatedUser user, long dealershipId)  // ADMIN, or ADVISOR of it
```

The rules stay in the pure `AccessPolicy`, unit-tested as before. The three new list operations
enforce them declaratively, by calling the policy from SpEL:

```java
@PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).isLinkedCustomer(principal)")
public List<VehicleResponse> vehicles(AuthenticatedUser caller)                // AccountService

@PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).isLinkedCustomer(principal)")
public List<AppointmentResponse> appointmentsOf(AuthenticatedUser caller)     // AppointmentService

@PreAuthorize("T(com.keyloop.scheduler.security.AccessPolicy).mayViewSchedule(principal, #dealershipId)")
public List<AppointmentResponse> dealershipDay(long dealershipId, LocalDate date)
```

Where it applies, and why not elsewhere:

| Operation | The decision needs | Enforcement |
| --- | --- | --- |
| `/me/vehicles`, `/me/appointments` | the caller only | `@PreAuthorize` |
| day schedule | the caller and the path's `dealershipId` | `@PreAuthorize` |
| booking | the caller and the request body; must be counted as `rejected` and come before the replay | stays in `BookingService`. A denial raised before the method body would skip the booking metric |
| read one appointment | the loaded appointment; must answer `404`, not `403` | stays in `AppointmentService`. `@PostAuthorize` answers `403` and would reveal that the appointment exists |
| cancel | the loaded appointment, before the update | stays in `AppointmentService`. `@PostAuthorize` runs after the method, when the cancellation may already be committed |

Mechanics:
- `@EnableMethodSecurity` on `SecurityConfiguration`.
- The annotations sit on `public` service methods, not on the package-private controllers. A CGLIB
  proxy might not intercept non-public methods, and a missing check would fail silently. On the
  service, the rule also holds for any future caller.
- The method-security interceptor wraps the transactional one, so a refused call opens no
  transaction and reads nothing. Request validation (`400`) still comes first, because Spring MVC
  validates arguments before invoking the controller.
- `AccessDeniedException` must become a `403 FORBIDDEN` problem. Today `GlobalExceptionHandler`'s
  catch-all `@ExceptionHandler(Exception.class)` would turn it into `500 INTERNAL_ERROR`. A dedicated
  handler maps it, with the request id, and logs at WARN without a stack trace. This also covers
  Spring Security's subclass `AuthorizationDeniedException`.
- SpEL is checked only at run time, so every annotated operation has an integration test for both an
  allowed and a refused caller. Parameter names (`#dealershipId`) come from `-parameters`, which the
  Spring Boot parent enables.
- Convention, recorded in `AGENTS.md`: use `@PreAuthorize` when the caller and the arguments decide.
  Check in the service when the decision needs loaded data, or must sit between business steps.

Per endpoint:
- `/me`: any authenticated caller. It is how a client learns that a token has no usable role.
- `/me/vehicles`, `/me/appointments`: not a linked customer → `403 FORBIDDEN`. The customer id comes
  only from the token, never from a parameter, so a customer cannot ask for someone else's data.
- The schedule checks in this order:
  1. permission (`403`, by `@PreAuthorize`), before anything is read, so a refused caller learns
     nothing;
  2. the dealership (`404`);
  3. the query.
  An advisor asking for another dealership gets `403`, not `404`. Dealerships are public, so this
  reveals nothing. It also differs on purpose from a single appointment, where `404` hides that the
  appointment exists.
- Customers see customer ids in their own appointments only. Advisors see the customer ids of their
  dealership's appointments, as the visibility rule already allows.

### 4. Queries

- Both lists use the `AppointmentView` projection with theta joins on dealership, bay and technician:
  one statement per request, with no N+1.
- Schedule: `a.dealershipId = :id and a.status = CONFIRMED and a.startAt >= :from and a.startAt < :to`. The bounds are
  `BusinessDay(date, zone, opens, closes).wholeDay()`, the same day window that availability uses,
  so the Auckland case (UTC+13) and daylight saving days follow from the zone. It is ordered by
  `startAt`, then `serviceBayId`.
- Customer: `a.customerId = :customerId`, ordered by `startAt`, then `id`.
- Indexes from V1 serve both lists, so there is no migration.
  - The schedule uses the partial index `appointment_dealership_start_idx (dealership_id, start_at)
    WHERE status = 'CONFIRMED'`. This is also why the schedule lists confirmed appointments only. A
    cancelled appointment no longer occupies a bay, and listing it would need a scan beyond that
    index.
  - The customer list uses `appointment_customer_idx (customer_id)` and keeps both statuses, as a
    history.

### 5. `/me`

- `role` is serialised as the enum name, or `null`. `customer` comes from `CustomerRepository` and
  `dealership` from `DealershipRepository`, as `ResourceRef(id, name)`. A missing record gives
  `null`.
- `/me` does not repeat the email: the client already has it from Supabase, and the backend logs
  only the user id.

### 6. OpenAPI

All four operations carry `@SecurityRequirement(name = "bearer")`, and the caller never appears as a
parameter.

## Concurrency

Every endpoint is a read-only transaction at `READ COMMITTED`. None takes the booking lock or sets
`lock_timeout`, so the reads never wait for a booking in progress. Each list is one statement, so it
sees one committed state: a booking that answered `201` before the read is listed, and a booking
still in flight is not. Cancelling and booking do not depend on these reads.

## Metrics and log events

No new metric or log event. Requests are counted and timed by `http_server_requests_seconds` with
their URI templates and appear in the access log, carrying the caller's `userId` in the MDC.

## Risks / Trade-offs

- [Unpaginated lists] → They are bounded by one customer's history and one dealership-day, and this
  is documented as an assumption. The `items` wrapper allows paging later without breaking clients.
- [List items show ids, not names, for customer, vehicle and service type] → Service types are in
  the public catalog. Showing customer and vehicle names on the day schedule is left as an open
  question for the web client.
- [`403` for staff on `/me/vehicles`] → Strict but explicit. A client checks `role` from `/me`
  before calling it.
- [Two enforcement styles in one codebase] → The convention in decision 3 says which to use, and the
  table records why each existing check stays where it is.
- [A typo in a SpEL expression shows up only at run time] → Every annotated operation is covered by
  allowed and refused integration tests.

## Migration Plan

Additive endpoints only: no schema change, and no change to existing responses. Rollback is a
redeploy.

## Open Questions

- Should day-schedule items carry the customer's name and the vehicle's make and model (additive
  fields), so the web client's board needs no extra calls? The proposal leaves them out until the
  board is designed.
