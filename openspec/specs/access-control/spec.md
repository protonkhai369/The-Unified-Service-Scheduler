# Access Control Specification

## Purpose
Know who calls the API and limit what they may do. Callers present Supabase Auth access tokens, which the
backend verifies locally against the project's published keys. Roles (customer, advisor, admin) and their
links come only from administrator-controlled `app_metadata`. The catalog, Swagger UI and health stay
public; everything else needs a token, and customers and advisors see only their own appointments or
those of their own dealership.

## Requirements
### Requirement: Bearer token authentication
The system SHALL authenticate callers with Supabase Auth access tokens sent as
`Authorization: Bearer <token>`. A token MUST be signed with one of the signing keys the Supabase
project publishes, MUST NOT be expired, and MUST carry the project's issuer
(`<SUPABASE_URL>/auth/v1`) and the audience `authenticated`. A request without a token to an
endpoint that requires one, or a request with a token that fails any of these checks, SHALL receive
`401` with code `UNAUTHENTICATED` and the header `WWW-Authenticate: Bearer`, as problem+json with a
`requestId`.

#### Scenario: Valid token accepted
- **WHEN** a request to `GET /api/v1/appointments/{id}` carries a valid admin token
- **THEN** the request is processed (`200` for an existing appointment)

#### Scenario: No token
- **WHEN** `POST /api/v1/appointments` is sent without an `Authorization` header
- **THEN** the response is `401` with code `UNAUTHENTICATED` and header `WWW-Authenticate: Bearer`
- **AND** nothing is persisted

#### Scenario: Expired token
- **WHEN** the token's `exp` is in the past
- **THEN** the response is `401` with code `UNAUTHENTICATED`

#### Scenario: Token signed by another key
- **WHEN** the token is well-formed but signed with a key the project does not publish
- **THEN** the response is `401` with code `UNAUTHENTICATED`

#### Scenario: Wrong issuer or audience
- **WHEN** the token was issued by another Supabase project, or its audience is not `authenticated`
- **THEN** the response is `401` with code `UNAUTHENTICATED`

#### Scenario: Malformed token
- **WHEN** the header is `Authorization: Bearer not-a-jwt`
- **THEN** the response is `401` with code `UNAUTHENTICATED`

### Requirement: Public endpoints
The catalog (`GET /api/v1/service-types`, `GET /api/v1/dealerships`,
`GET /api/v1/dealerships/{id}`), Swagger UI with its OpenAPI document, and `/actuator/health` with
its liveness and readiness groups SHALL be reachable without a token. Every other path SHALL
require a valid token, including paths that do not exist. A request to a public endpoint that
carries an invalid token SHALL also receive `401`.

#### Scenario: Catalog without a token
- **WHEN** `GET /api/v1/dealerships` is sent without a token
- **THEN** the response is `200`

#### Scenario: Health without a token
- **WHEN** `GET /actuator/health/readiness` is sent without a token
- **THEN** the response is `200`

#### Scenario: Unknown path without a token
- **WHEN** `GET /api/v1/no-such-path` is sent without a token
- **THEN** the response is `401` with code `UNAUTHENTICATED`

#### Scenario: Availability search needs a token
- **WHEN** `GET /api/v1/dealerships/{id}/availability` is sent without a token
- **THEN** the response is `401` with code `UNAUTHENTICATED`
- **AND** with a valid token of any role, or of no role, the search is processed

#### Scenario: Public endpoint with an invalid token
- **WHEN** `GET /api/v1/dealerships` is sent with an expired token
- **THEN** the response is `401` with code `UNAUTHENTICATED`

### Requirement: Roles from the token
The system SHALL take the caller's role from the token claim `app_metadata.scheduler_role`
(`customer`, `advisor` or `admin`), the customer from `app_metadata.customer_id` and the dealership
from `app_metadata.dealership_id`. Supabase lets only administrators change `app_metadata`, never
the user. Claims under `user_metadata` MUST be ignored. A `customer` MUST have a `customer_id`, and
an `advisor` MUST have a `dealership_id`. A valid token without a known role, or without the id its
role needs, SHALL be authenticated but receive `403` with code `FORBIDDEN` on every appointment
endpoint.

#### Scenario: Token without a role
- **WHEN** a valid token has no `app_metadata.scheduler_role` and books an appointment
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Role claimed in user metadata is ignored
- **WHEN** a valid token has `user_metadata.scheduler_role` `admin` but no role in `app_metadata`
- **THEN** reading any appointment answers `403` with code `FORBIDDEN`

#### Scenario: Customer token without a customer id
- **WHEN** a token with role `customer` has no `customer_id` and books an appointment
- **THEN** the response is `403` with code `FORBIDDEN`

### Requirement: Booking permissions
`POST /api/v1/appointments` SHALL be allowed only when one of these holds:
- the caller is a `customer` whose `customer_id` equals the request's `customerId`;
- the caller is an `advisor` whose `dealership_id` equals the request's `dealershipId`, for any
  customer;
- the caller is an `admin`.

Otherwise the system SHALL respond `403` with code `FORBIDDEN` and persist nothing. The check MUST
run after request validation (`400`) and before the idempotency replay and every other booking
rule.

#### Scenario: Customer books for themselves
- **WHEN** a customer token with `customer_id` 2 books for customer 2's vehicle
- **THEN** the response is `201`

#### Scenario: Customer books for someone else
- **WHEN** a customer token with `customer_id` 2 books with `customerId` 1
- **THEN** the response is `403` with code `FORBIDDEN` and nothing is persisted

#### Scenario: Advisor books at their dealership
- **WHEN** an advisor token with `dealership_id` 2 books any customer's vehicle at dealership 2
- **THEN** the response is `201`

#### Scenario: Advisor books at another dealership
- **WHEN** an advisor token with `dealership_id` 2 books at dealership 1
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Forbidden request cannot replay another booking
- **WHEN** customer 1 booked with `Idempotency-Key: k1`, and a token with `customer_id` 2 sends the identical request and key
- **THEN** the response is `403` with code `FORBIDDEN`, not the replayed appointment

### Requirement: Appointment visibility
`GET /api/v1/appointments/{id}` and `POST /api/v1/appointments/{id}/cancel` SHALL apply to an
appointment only when one of these holds:
- the caller is the appointment's `customer`;
- the caller is an `advisor` of the appointment's dealership;
- the caller is an `admin`.

For any other existing appointment, the system SHALL respond `404` with code
`APPOINTMENT_NOT_FOUND`, exactly as for an unknown id, and SHALL change nothing.

#### Scenario: Customer reads their appointment
- **WHEN** a customer reads one of their own appointments
- **THEN** the response is `200`

#### Scenario: Customer reads another customer's appointment
- **WHEN** a customer reads an appointment of another customer
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`

#### Scenario: Customer cannot cancel another customer's appointment
- **WHEN** a customer cancels an appointment of another customer
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`
- **AND** the appointment remains `CONFIRMED`

#### Scenario: Advisor cancels at their dealership
- **WHEN** an advisor cancels an appointment of their own dealership
- **THEN** the response is `200` with status `CANCELLED`

#### Scenario: Advisor reads another dealership's appointment
- **WHEN** an advisor reads an appointment of another dealership
- **THEN** the response is `404` with code `APPOINTMENT_NOT_FOUND`

#### Scenario: Admin reads any appointment
- **WHEN** an admin reads any existing appointment
- **THEN** the response is `200`

### Requirement: Operational endpoints for admins
`/actuator/prometheus` and `/actuator/metrics` SHALL require a token with role `admin`. Without a
token they SHALL answer `401` with code `UNAUTHENTICATED`; with any other role they SHALL answer
`403` with code `FORBIDDEN`.

#### Scenario: Prometheus for an admin
- **WHEN** an admin requests `/actuator/prometheus`
- **THEN** the response is `200` and contains `scheduler_bookings_total`

#### Scenario: Prometheus for a customer
- **WHEN** a customer requests `/actuator/prometheus`
- **THEN** the response is `403` with code `FORBIDDEN`

