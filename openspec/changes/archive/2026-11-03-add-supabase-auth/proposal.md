## Why

Today anyone who can reach the API can book, read and cancel any appointment, including another
customer's. A dealership system that replaces manual booking must know who is calling and limit
customers to their own vehicles and staff to their own dealership. The project's PostgreSQL already
runs on Supabase, and Supabase Auth issues signed access tokens. Its signing keys are published as a
JWKS (ES256, checked on the live project), so the backend can verify tokens without holding any
Supabase secret. This change serves the core booking flow (requirements 1 and 3: an appointment
belongs to a customer) and the "build for the future" concerns of reliability and maintainability:
access rules are explicit and tested rather than implied.

## What Changes

- **BREAKING**: every appointment endpoint requires `Authorization: Bearer <Supabase access token>`.
  Without a valid token the answer is `401 UNAUTHENTICATED`, with a `WWW-Authenticate: Bearer`
  header, as problem+json.
- The backend is an OAuth2 resource server.
  - It verifies the token signature (ES256, keys from the project's JWKS), expiry, issuer
    (`<SUPABASE_URL>/auth/v1`) and audience (`authenticated`).
  - Logging in, signing up and refreshing tokens stay with Supabase Auth. The backend has no login
    endpoint and never sees a password.
- Three roles, read from the token's `app_metadata`, which only an administrator (the Supabase
  dashboard or the Admin API) can change, never the user:
  - `customer` with a `customer_id`: books only for that customer, and sees or cancels only that
    customer's appointments;
  - `advisor` with a `dealership_id`: books for any customer at that dealership, and sees or
    cancels that dealership's appointments;
  - `admin`: everything, including `/actuator/prometheus` and `/actuator/metrics`.
- Forbidden bookings answer `403 FORBIDDEN`. The check runs before the idempotency replay and before
  every booking rule.
- An appointment the caller may not see answers `404 APPOINTMENT_NOT_FOUND`, exactly like an
  unknown id, so the response never confirms that someone else's appointment exists.
- Public without a token: the catalog (`GET /api/v1/service-types`, `/api/v1/dealerships`,
  `/api/v1/dealerships/{id}`), Swagger UI with the OpenAPI document, and `/actuator/health`
  (including liveness and readiness). Everything else is denied by default. An unknown path therefore
  answers `401` without a token and `404` with one.
- Swagger UI gains an "Authorize" button for a bearer token.
- Log lines of an authenticated request carry the Supabase user id (`userId`, a UUID, not the email).
- New error codes `UNAUTHENTICATED` (401) and `FORBIDDEN` (403).
- A script creates demo users in Supabase Auth for each role, using the project's secret key from
  the git-ignored `.env`. A short guide shows how to obtain a token for cURL, Postman and Swagger.

## Capabilities

### New Capabilities

- `access-control`: bearer token authentication, public endpoints, roles from `app_metadata`,
  booking permissions, appointment visibility, and operational endpoints reserved to admins.

### Modified Capabilities

- `error-responses`: the "Framework error" scenario now needs an authenticated client, because an
  unknown path answers `401` without a token.

## Non-goals

- Login, sign-up, password reset or token refresh endpoints in the backend; user or role management
  endpoints.
- Row Level Security. The backend reaches PostgreSQL over JDBC as the database owner, and V1 already
  revokes the Data API roles.
- Idempotency keys scoped per user. Keys stay global; the authorization check before the replay
  stops a caller from replaying someone else's booking.
- CORS (there is no browser client), rate limiting, mTLS.
- A separate management port or scraper credentials for Prometheus. Admin-only access is the
  interim rule.
- Revoking tokens before they expire. A role change takes effect when the user's token is next
  refreshed (at most one hour).

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- A Supabase user is linked to a customer, or to a dealership for staff, by an administrator setting
  `app_metadata` (`scheduler_role`, `customer_id`, `dealership_id`). There is no self-service
  linking.
- An advisor may book for any customer at their own dealership, and sees all of that dealership's
  appointments.
- A valid token without a known role, or a customer token without `customer_id` (an advisor token
  without `dealership_id`), is authenticated but has no permission on appointments.
- Another user's appointment is reported as not found rather than forbidden.
- Catalog data (dealerships, bays, technician names, service types) is public.

## Impact

- `pom.xml`: `spring-boot-starter-security-oauth2-resource-server` and its `-test` starter.
- New package `security`:
  - security configuration, the JWT-to-user converter and the `userId` log filter;
  - `AuthenticatedUser` and the pure `AccessPolicy`.
- `web.error`: problem responses for 401 and 403. `ErrorCode`: `UNAUTHENTICATED`, `FORBIDDEN`.
- `appointment`: the controllers pass the caller to the services. `BookingService` checks the caller
  first, and `AppointmentService` checks visibility before reading or cancelling.
- `application.yml` and `.env.example`: `SUPABASE_URL` (default: the Supabase CLI's local
  `http://127.0.0.1:54321`), plus `SUPABASE_PUBLISHABLE_KEY` and `SUPABASE_SECRET_KEY` for the demo
  script only.
- Tests: a test key pair and token factory replace Supabase's keys in the shared context, the
  existing appointment tests send an admin token, and new authentication and authorization tests are
  added.
- `scripts/supabase-demo-users.py` and docs: `SYSTEM_DESIGN.md`, `AGENTS.md`, `openspec/config.yaml`,
  `AI_LOG.md`.
- No schema change.
