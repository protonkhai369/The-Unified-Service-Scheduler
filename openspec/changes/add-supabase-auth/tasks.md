## 1. Dependencies and configuration

- [x] 1.1 Add `spring-boot-starter-security-oauth2-resource-server` and `spring-boot-starter-security-oauth2-resource-server-test`. Add the `spring.security.oauth2.resourceserver.jwt` settings of design decision 1 to `application.yml`. Add `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` and `SUPABASE_SECRET_KEY` placeholders to `.env.example`
- [x] 1.2 Add `TestSecurityConfiguration` (`TestTokens` with an EC key pair, and a `JwkSetUriJwtDecoderBuilderCustomizer` that serves its public key to Boot's own decoder, design decision 8) imported by `DatabaseIntegrationTest`. Add a probe `SecurityFilterChain` permitting `/api/test-probe/**` to `ProbeConfiguration`. Make `AppointmentApiTestSupport` send an admin token by default. Send tokens in the unknown-path and Prometheus tests. Confirm the existing suite passes with security on, in one context

## 2. Authentication (tests first)

- [x] 2.1 Write `AuthenticationIntegrationTest` for "Bearer token authentication", "Public endpoints" and the error-responses scenarios "Framework error" and "Authentication error": problem+json, `WWW-Authenticate: Bearer`, and `requestId` equal to the header
- [x] 2.2 Implement `SecurityConfiguration` (design decision 3; the decoder and its validators are Boot's, decision 1), `ProblemAuthenticationEntryPoint` and `ProblemAccessDeniedHandler` (decision 5), and the `UNAUTHENTICATED` and `FORBIDDEN` error codes; make 2.1 pass

## 3. Roles and policy (tests first)

- [x] 3.1 Write unit tests for `SupabaseJwtConverter`:
  - each role;
  - case-insensitive values;
  - an unknown role becomes `null`;
  - numeric and string ids;
  - `user_metadata` is ignored.
- [x] 3.2 Write unit tests for `AccessPolicy.mayBook`/`maySee`, covering every row of the booking and visibility rules: a customer with and without `customer_id`, an advisor with and without `dealership_id`, an admin, and no role
- [x] 3.3 Implement `Role`, `AuthenticatedUser`, `SupabaseJwtConverter` and `AccessPolicy` in package `security`; make 3.1 and 3.2 pass

## 4. Booking permissions (tests first)

- [x] 4.1 Write `BookingPermissionIntegrationTest` with one test per scenario of "Booking permissions" and "Roles from the token" (the booking cases); assert that nothing is persisted on `403`, and that a forbidden request does not replay
- [x] 4.2 Pass `@AuthenticationPrincipal AuthenticatedUser` from `AppointmentController` to `BookingService.book`, and check `mayBook` as its first step; make 4.1 pass and keep the booking suite green

## 5. Appointment visibility (tests first)

- [x] 5.1 Write `AppointmentVisibilityIntegrationTest` with one test per scenario of "Appointment visibility", including that a forbidden cancel leaves the appointment `CONFIRMED`, and the `user_metadata` scenario on a read
- [x] 5.2 Pass the caller to `AppointmentService.appointment` and `cancel`; check `maySee` after loading and before the conditional update (design decision 4); make 5.1 pass

## 6. Operational endpoints, OpenAPI and logs (tests first)

- [x] 6.1 Write tests for "Operational endpoints for admins" (admin `200`, customer `403`, no token `401`); for the OpenAPI document declaring the bearer scheme on the appointment operations; and, with `OutputCaptureExtension`, that a booking's log line carries the token's `sub` and not the email
- [x] 6.2 Add the actuator rules, the springdoc `@SecurityScheme`/`@SecurityRequirement`, and the `userId` MDC filter with the console pattern; make 6.1 pass

## 7. Demo users and documentation

- [x] 7.1 Write `scripts/supabase-demo-users.py` (standard-library Python: safe JSON without `jq`). It reads `SUPABASE_URL` and `SUPABASE_SECRET_KEY` from `.env` and `DEMO_USER_PASSWORD` from the environment, and creates or updates four users through the Supabase Admin API: `admin`, `advisor` of the Saigon dealership, `customer` Emma, `customer` Duc, with their `app_metadata`. It never prints keys or passwords
- [ ] 7.2 Run the script against the live project only after the user confirms and has put `SUPABASE_SECRET_KEY` in `.env`. Fetch a token per role from `/auth/v1/token` with the publishable key, and run the cURL checks against `spring-boot:run`:
  - customer: own booking `201`, someone else's `403`;
  - advisor: other dealership `403`;
  - no token: `401`;
  - admin: Prometheus `200`.
- [x] 7.3 Update `docs/SYSTEM_DESIGN.md` (access-control section, flow diagram, roles table, assumptions), `AGENTS.md` and `openspec/config.yaml` (security rules: deny by default, `AccessPolicy`, check before replay, tests mint tokens), and `docs/AI_LOG.md`

## 8. Verification

- [x] 8.1 Run `./mvnw clean test` and quote the Surefire summary; every test passes
- [x] 8.2 Map every scenario of `specs/access-control` and the modified error-responses scenarios to a passing test or to a 7.2 check, and list any gap
- [x] 8.3 Run `openspec validate add-supabase-auth --strict` and confirm it passes
