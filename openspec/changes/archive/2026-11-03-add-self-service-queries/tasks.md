## 1. Access rules (tests first)

- [x] 1.1 Unit-test `AccessPolicy.isLinkedCustomer` and `AccessPolicy.mayViewSchedule` for:
  - a customer with and without `customer_id`;
  - an advisor of the dealership, of another dealership, and without `dealership_id`;
  - an admin;
  - no role.
- [x] 1.2 Implement both methods; make 1.1 pass
- [x] 1.3 Write a test through a probe endpoint guarded by `@PreAuthorize("false")`: the denial answers a `403` problem with code `FORBIDDEN` and the request id, not `500`
- [x] 1.4 Add `@EnableMethodSecurity` and the `AccessDeniedException` handler (403, logged at WARN without a stack trace); make 1.3 pass and keep the suite green

## 2. Caller profile (tests first)

- [x] 2.1 Write `AccountIntegrationTest` with one test per scenario of "Caller profile" and "Caller's vehicles"
- [x] 2.2 Add `VehicleRepository.findByCustomerIdOrderById`, then `AccountResponse`, `VehicleResponse`, `AccountService` (with `@PreAuthorize` on `vehicles`) and `AccountController` (design decisions 2, 3 and 5); make 2.1 pass

## 3. Appointment lists (tests first)

- [x] 3.1 Extend `AppointmentRepositoryIntegrationTest` for `findViewsOfCustomer` and `findViewsStartingBetween`:
  - ordering;
  - half-open day bounds;
  - other dealerships and customers excluded;
  - the schedule returns confirmed appointments only, and the customer list returns both statuses.
- [x] 3.2 Write `AppointmentListIntegrationTest` with one test per scenario of "Caller's appointments" and "Dealership day schedule". This includes the Auckland local-day case, and a booking that is listed right after its `201`
- [x] 3.3 Implement the two queries, `AppointmentService.appointmentsOf` and `dealershipDay` (`@PreAuthorize`, then the dealership, then the query), and `AppointmentListController`; make 3.1 and 3.2 pass

## 4. OpenAPI and documentation

- [x] 4.1 Extend `OpenApiIntegrationTest`: the four operations carry the bearer scheme, and the caller never appears as a parameter; then add the annotations
- [x] 4.2 Update `docs/SYSTEM_DESIGN.md` (API table, roles table, assumptions from the proposal), `AGENTS.md` (current state, and the `@PreAuthorize` versus service-check convention), `openspec/config.yaml`, and `docs/AI_LOG.md`

## 5. Verification

- [x] 5.1 Run `./mvnw clean test` and quote the Surefire summary; every test passes
- [x] 5.2 Map every scenario of `specs/caller-profile` and `specs/appointment-lists` to a passing test and list any gap
- [x] 5.3 Run `openspec validate add-self-service-queries --strict` and `openspec validate --all --strict`
