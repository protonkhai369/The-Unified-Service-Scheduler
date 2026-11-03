## Why

A signed-in caller can book, read and cancel an appointment, but cannot find anything without
already knowing its id. Two gaps block a usable client, including the planned web client:
- To book, a customer must send `customerId` and `vehicleId`, yet no endpoint tells a caller who they
  are or which vehicles they own.
- A customer cannot list their own appointments. An advisor cannot see their dealership's day,
  which is the screen a service desk works from.

This change serves Scenario A's booking flow (requirement 1: a customer books for their own vehicle)
and requirement 3, since a confirmed appointment must be findable afterwards. It also serves
maintainability: the backend stays the one place that interprets the token, so clients do not
re-parse `app_metadata` themselves.

## What Changes

Four read-only endpoints. Each needs a bearer token and is scoped by the caller's role through the
existing access rules.

| Endpoint | Who | Returns |
| --- | --- | --- |
| `GET /api/v1/me` | any valid token | the caller: user id, role, and the linked customer or dealership (`{id, name}`) |
| `GET /api/v1/me/vehicles` | customer | `{"items": [...]}` of the caller's vehicles: `id`, `vin`, `make`, `model`, `modelYear` |
| `GET /api/v1/me/appointments` | customer | `{"items": [...]}` of the caller's appointments, both statuses, earliest start first |
| `GET /api/v1/dealerships/{dealershipId}/appointments?date=` | advisor of that dealership, admin | `{"items": [...]}` of the confirmed appointments starting on that local day, earliest start first |

- Appointments in the lists have the shape that `GET /api/v1/appointments/{id}` already returns.
- The three list operations enforce their access rules with Spring Security's `@PreAuthorize`,
  calling the existing pure `AccessPolicy`. Booking, reading one appointment and cancelling keep their
  checks inside the service, for the reasons in design decision 3.
- A caller outside an endpoint's audience gets `403 FORBIDDEN`. This covers a token without a usable
  role, an advisor or admin on `/me/vehicles`, and a customer on a dealership's day. The dealership
  list is public, so `403` reveals nothing.
- No new error code, table or migration.

The earlier plan had one `GET /api/v1/appointments?dealershipId=&date=`. This change splits it by
use case instead (see design decision 1).

## Capabilities

### New Capabilities

- `caller-profile`: who the caller is (`/me`) and which vehicles a customer owns (`/me/vehicles`).
- `appointment-lists`: a customer's own appointments, and one dealership's appointments on one local
  day.

### Modified Capabilities

None. The access rules of the active change `add-supabase-auth` are applied, not changed.

## Non-goals

- Searching customers or listing another customer's vehicles. Advisors would need that to book on a
  customer's behalf; it involves personal data and gets its own change.
- Adding customer names, vehicle descriptions or service type names to appointments. A list item
  keeps today's shape; the web client can look up service types in the public catalog.
- Pagination, status or date-range filters, and sorting options.
- Creating or editing customers and vehicles. They are master data owned by the dealer management
  system.
- The web client itself.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- A customer's appointment history and a dealership's day are small enough to return unpaginated.
  The `items` wrapper leaves room for paging later.
- Lists are ordered by start time, then id (the day schedule orders by bay before id). This is an
  exception to "collections ordered by id", because appointment ids are random UUIDs and carry no
  order.
- The day schedule's `date` is the dealership's local date, like the availability search. An
  appointment belongs to the local day in which it starts.
- `/me` reports a link that points at a missing customer or dealership as `null`, rather than failing.

## Impact

- Code:
  - New controller and service for `/me` and `/me/vehicles`, and a vehicle response.
  - New list methods on the appointment read service, with two list queries on
    `AppointmentRepository`.
  - `VehicleRepository.findByCustomerIdOrderById`.
  - Two pure rules added to `AccessPolicy`.
  - `@EnableMethodSecurity`, and an `AccessDeniedException` handler that answers a `403 FORBIDDEN`
    problem. Without that handler, the catch-all exception handler would turn the denial into a
    `500`.
- OpenAPI: four operations carrying the bearer scheme.
- Tests: unit tests for the new rules; integration tests per endpoint, roles and edge cases.
- Docs: `docs/SYSTEM_DESIGN.md` (API table, roles table, assumptions), `AGENTS.md`,
  `openspec/config.yaml`, `docs/AI_LOG.md`.
- No schema change.
