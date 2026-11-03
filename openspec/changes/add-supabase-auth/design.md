## Context

- The API has no authentication. Appointment endpoints accept any caller. `RequestIdFilter` runs at
  the highest precedence. Errors raised outside Spring MVC reach `ProblemErrorController` (`/error`),
  and every error is problem+json built by `Problems.create`.
- The Supabase project signs access tokens with an asymmetric key. Its JWKS
  (`<SUPABASE_URL>/auth/v1/.well-known/jwks.json`) publishes one `EC`/`ES256`/`P-256` key, checked
  on 2026-11-03.
- A Supabase access token carries, among others:
  - `sub` (user UUID);
  - `aud: "authenticated"`;
  - `iss: "<SUPABASE_URL>/auth/v1"`;
  - `exp`;
  - `role: "authenticated"` (a PostgreSQL role, unrelated to ours);
  - `app_metadata` (only writable with the secret key or the dashboard);
  - `user_metadata` (writable by the user).
- Boot 4.1.2 starters: `spring-boot-starter-security-oauth2-resource-server` and
  `spring-boot-starter-security-oauth2-resource-server-test`.
- Tests share one context through `DatabaseIntegrationTest`. Test-only beans live in configurations
  that the base class imports (`ProbeConfiguration`).

## Goals / Non-Goals

**Goals:**
- Verify Supabase tokens locally, with no Supabase secret and no network call per request (the JWKS
  is cached).
- Make every access rule explicit, tested, and kept in one pure class.
- Keep the error format: 401 and 403 are problem+json with `code` and `requestId`.

**Non-Goals:** login or user-management endpoints, RLS, per-user idempotency keys, CORS, token
revocation, scraper credentials (see the proposal).

## Decisions

### 1. Token verification: Spring Security resource server with JWKS

```yaml
spring.security.oauth2.resourceserver.jwt:
  jwk-set-uri: ${SUPABASE_URL:http://127.0.0.1:54321}/auth/v1/.well-known/jwks.json
  issuer-uri:  ${SUPABASE_URL:http://127.0.0.1:54321}/auth/v1
  jws-algorithms: ES256
  audiences: authenticated
```

- With `jwk-set-uri` set, Boot builds a `NimbusJwtDecoder` from the JWKS. `issuer-uri` then only adds
  an issuer validator, with no discovery call at startup, so the application starts even when
  Supabase is unreachable. The JWKS is fetched on the first token and cached, and an unknown `kid`
  triggers a refetch, which handles key rotation.
- `jws-algorithms: ES256` is required: Boot defaults to RS256 and would reject every Supabase token.
  A test asserts that an ES256 token is accepted.
- Validators: signature, `exp`/`nbf` (60 s clock skew, Spring's default), issuer and audience, all
  built by Spring Boot from these properties (`JwtValidators.createDefaultWithValidators`). The
  application defines no `JwtDecoder` of its own. Token expiry uses Spring's system clock rather than
  the application `Clock`: tokens are real-time artefacts, while the booking rules use business time.
- The default `SUPABASE_URL` is the Supabase CLI's local address, so the application starts without
  `.env`. Then only public endpoints work, unless a local Supabase runs.

Alternative: the legacy HS256 shared JWT secret. Rejected: the project uses asymmetric keys, and a
shared secret would put a credential in the backend.

### 2. From JWT to `AuthenticatedUser`

`SupabaseJwtConverter` (`Converter<Jwt, AbstractAuthenticationToken>`) reads only `sub` and
`app_metadata`:

```java
public record AuthenticatedUser(UUID userId, @Nullable Role role, @Nullable Long customerId,
        @Nullable Long dealershipId) { }
public enum Role { CUSTOMER, ADVISOR, ADMIN }
```

- `scheduler_role` values are case-insensitive. An unknown value gives `role = null`.
  `customer_id` and `dealership_id` accept JSON numbers or numeric strings; anything else gives
  `null`.
- The granted authority is `ROLE_ADMIN`, `ROLE_ADVISOR` or `ROLE_CUSTOMER`, and none for a token
  without a valid role. URL rules (decision 3) use it only for the actuator.
- A `sub` that is not a UUID is not a Supabase user token: the converter throws
  `InvalidBearerTokenException`, which answers `401`.
- Controllers receive the caller as `@AuthenticationPrincipal AuthenticatedUser`.

Alternatives:
- Roles in a database table: rejected by the user in explore, in favour of `app_metadata`. That
  avoids a migration and a query per request.
- Reading `user_metadata`: never, because users can edit it.

### 3. URL rules: deny by default

```
SecurityFilterChain (stateless, no sessions, CSRF off: no cookies are used)
  dispatcherTypeMatchers(ERROR, FORWARD)               permitAll   ← /error must render 401/403/500
  GET  /api/v1/service-types, /api/v1/dealerships/**   permitAll
  /v3/api-docs/**, /swagger-ui/**, /swagger-ui.html    permitAll
  /actuator/health, /actuator/health/**                permitAll
  /actuator/**                                         hasRole(ADMIN)   <- prometheus, metrics, info
  anyRequest()                                         authenticated
```

- Every actuator endpoint except health is admin-only, not just Prometheus and metrics: `info` and
  the links page are operational too, and the stricter default costs nothing.
- The catalog paths are permitted for every method, so `DELETE /api/v1/service-types` still answers
  `405`, as the error-responses spec requires.
- A request that carries an invalid token gets `401` even on a public path, because
  `BearerTokenAuthenticationFilter` rejects it before authorization. The access-control spec makes
  this explicit.
- `RequestIdFilter` (highest precedence) runs before the security chain (order −100), so 401 and 403
  responses carry `X-Request-Id`, and the access log records them.

### 4. Resource rules: a pure `AccessPolicy`

Package `security` holds the Spring configuration and the converter. The rules themselves are a
class with no Spring dependency, unit-tested:

```java
static boolean mayUseAppointments(AuthenticatedUser user)   // a role with the id it needs
static boolean mayBook(AuthenticatedUser user, long dealershipId, long customerId)
static boolean maySee(AuthenticatedUser user, long appointmentDealershipId, long appointmentCustomerId)
```

`mayBook` and `maySee` apply the same rule today (own customer, own dealership, or admin). They stay
separate because booking and visibility may diverge.

Booking (`BookingService.book(request, key, user)`):
- The first statement is `if (!mayBook(...)) throw ApiException(FORBIDDEN)`.
- That puts the check before the replay lookup. A customer can write any `customerId` into the body,
  so if the replay ran first, a caller who knew another customer's key and body could read that
  booking.
- The `403` is counted as `scheduler_bookings_total{outcome="rejected"}`, as for every booking
  error code.

Reading (`AppointmentService.appointment(id, user)`):
- If `!mayUseAppointments(user)`, throw `FORBIDDEN` (spec: a token without a usable role gets `403`
  on every appointment endpoint).
- Load the view, then if `!maySee(...)`, throw `APPOINTMENT_NOT_FOUND`.

Cancelling (`AppointmentService.cancel(id, user)`), in one transaction:
1. Check `mayUseAppointments` (`403`), then load the view: if it is missing or `!maySee`, answer
   `APPOINTMENT_NOT_FOUND`.
2. Run the existing conditional update.
3. Re-read.

The check must come **before** the update; otherwise a customer could cancel someone else's
appointment and only then be told it was not found. The values checked (`customer_id`,
`dealership_id`) never change after insert, so a concurrent request cannot invalidate the check
between steps 1 and 2. Concurrent cancellations behave as before.

Alternative: `@PreAuthorize` with SpEL on services. Rejected: the rules need the request body or the
loaded appointment, and SpEL strings are harder to unit-test than a pure method.

### 5. 401 and 403 as problems

- `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` live in `web.error`.
- They only set the status with `sendError`, plus `WWW-Authenticate: Bearer` on 401 (RFC 6750). The
  container's error dispatch then reaches `ProblemErrorController`, which already renders every
  failure outside Spring MVC: `ErrorCode.forStatus` maps 401 to `UNAUTHENTICATED` and 403 to
  `FORBIDDEN`, and `Problems.log` logs at WARN without a stack trace, like every 4xx. There is one
  rendering path, and no JSON written by hand in a filter.
- The error dispatch is permitted (decision 3) and carries the same `X-Request-Id`.
- `FORBIDDEN` thrown as `ApiException` from services goes through the existing
  `GlobalExceptionHandler`.
- Detail texts never echo the token or its claims.

### 6. User id in log lines

`UserIdLogFilter`, placed after `BearerTokenAuthenticationFilter`, puts the `sub` UUID into the MDC
as `userId` for the rest of the chain, and removes it in `finally`.
- It also runs on the error dispatch, so the WARN line of a `403` names the caller.
- It is not a bean, so Boot does not register it a second time as a servlet filter.
- The console pattern becomes `[requestId userId]`, or `[requestId]` without a caller (`%replace`
  drops the empty part). ECS logs get a `userId` field.
- Emails and names are never logged.
- The access log line is written after the chain returns, so it carries the request id only.

### 7. OpenAPI

`@SecurityScheme(name = "bearer", type = HTTP, scheme = "bearer", bearerFormat = "JWT")` and
`@SecurityRequirement(name = "bearer")` on `AppointmentController`, so Swagger UI shows
"Authorize" and sends the token on appointment calls.

### 8. Tests without Supabase

`TestSecurityConfiguration` is imported by `DatabaseIntegrationTest`, so there is still one context.
`DatabaseIntegrationTest` sets `SUPABASE_URL=https://test.supabase.co`. The configuration provides:
- `TestTokens`, holding an EC P-256 key pair generated at startup. It mints signed tokens shaped like
  Supabase's:
  - `admin()`, `customer(id)`, `advisor(dealershipId)`;
  - `withoutRole()`, `customerWithoutCustomerId()`, `adminInUserMetadata()`;
  - `expired()`, `fromAnotherProject()`, `withWrongAudience()`;
  - `signedByAnUnpublishedKey()` (same `kid`, other key).
- A `JwkSetUriJwtDecoderBuilderCustomizer`. It gives Boot's decoder a `RestTemplate` that answers the
  JWKS URI with the test public key from memory, and fails a download from any other URI.

The application therefore runs its **real decoder**, built from `application.yml` (ES256 only,
issuer, audience, expiry). The tests would fail if `jws-algorithms: ES256` were removed.

Alternative: a `@Primary JwtDecoder` built from the test key, sharing a validator factory with
production. Rejected during implementation: it would replace the very decoder under test, and it
would need a production factory that Boot already provides.

`ProbeConfiguration` adds a `SecurityFilterChain` ordered before the main one that permits
`/api/test-probe/**`, so the error-pipeline probes keep testing errors rather than authentication.

Existing tests:
- `AppointmentApiTestSupport` sends an admin token by default, and has `bookAs`, `getAs` and
  `cancelAs` for other callers;
- the unknown-path and Prometheus tests send tokens;
- catalog tests stay anonymous.

## Metrics and log events

- No new metric. `http_server_requests_seconds` records `401` and `403` by status. Forbidden
  bookings count as `rejected` (decision 4). 401s never reach the booking service, so they are not
  counted as bookings.
- WARN problem lines for 401 and 403 (`401 UNAUTHENTICATED`, `403 FORBIDDEN`), and `userId` in the
  MDC.

## Risks / Trade-offs

- [Supabase is unreachable when the JWKS is first needed] → New tokens fail with `401` until it is
  reachable. After the first fetch the keys are cached, so a short outage does not affect
  already-known keys.
- [A role change does not apply to tokens already issued] → Access tokens live about an hour. This
  is accepted and documented; revocation is a non-goal.
- [Prometheus scraping with an hour-long user token is impractical] → Admin-only is the interim rule.
  A separate management port behind the network is the production follow-up.
- [Everything except a short list is denied, so new public endpoints must be added to the list] →
  This is the intended default; an omission fails closed with `401`.
- [`ES256` is forgotten or the JWKS changes algorithm] → Every token is rejected loudly with `401`.
  The integration tests sign ES256 tokens.

## Migration Plan

- Set `SUPABASE_URL` in the environment (in `.env` locally).
- Create the demo users with `scripts/supabase-demo-users.py`, using `SUPABASE_SECRET_KEY` in `.env`.
  The script is standard-library Python, so no `jq` is needed to build or read JSON safely. The script only
  runs after the user approves it, because it creates users in the live Supabase project.
- Clients must now send a bearer token on appointment endpoints (**BREAKING**). The Swagger UI and
  cURL guide show how.
- Rollback means redeploying the previous build. There is no schema change.

## Open Questions

None.
