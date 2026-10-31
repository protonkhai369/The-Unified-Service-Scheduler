## 1. Test infrastructure

- [x] 1.1 Switch `DatabaseIntegrationTest` to `webEnvironment = RANDOM_PORT` and give subclasses a `RestTestClient` bound to the local port; confirm the existing schema tests and `contextLoads` still pass with one shared context
- [x] 1.2 Add a test-only `@TestConfiguration`, imported by `DatabaseIntegrationTest`, with probe endpoints under `/api/test-probe/`: a `POST` taking a `@Valid` JSON record with a required positive numeric field and a second required field; a `GET` with a required query parameter; a `GET` with a required header; a `GET` that throws an unexpected `RuntimeException`; a `GET` that throws `ResponseStatusException(GONE)`. Also add a filter that throws for `/api/test-probe/filter-failure`

## 2. Request id and access log (tests first)

- [x] 2.1 Write a unit test for the request id rule: `abc-123` is kept; an id with a space, an empty id and a 65-character id are replaced; a missing id yields a new UUID
- [x] 2.2 Write `RequestIdIntegrationTest`: a caller's `X-Request-Id: abc-123` is echoed; an unsafe id is replaced by a UUID; an id is generated when none is sent; the header is present on success and on error responses; captured output holds an access log line like `GET /api/test-probe/... -> 200 (N ms)` that carries the request id; `/actuator/health` writes no access log line
- [x] 2.3 Implement the request id filter (highest precedence, also on ERROR dispatch reusing the stored id, request attribute, MDC `requestId` cleared in `finally`, response header set before the chain) and the `/api/**` access log line; add `logging.pattern.correlation` to `application.yml`; make 2.1 and 2.2 pass

## 3. Global error handling (tests first)

- [x] 3.1 Write `ErrorCodeTest`: `forStatus` maps 404 → `NOT_FOUND`, 405 → `METHOD_NOT_ALLOWED`, 415 → `UNSUPPORTED_MEDIA_TYPE`, other 4xx (400, 410, 413) → `REQUEST_REJECTED`, 5xx (500, 503) → `INTERNAL_ERROR`; `detail(args)` fills the template (`DEALERSHIP_NOT_FOUND` with 999 → "Dealership 999 does not exist."); every constant name is upper snake case and appears in at least one spec file under `openspec/specs` or `openspec/changes`
- [x] 3.2 Write `ErrorHandlingIntegrationTest` against the probe endpoints:
  - an unknown path gives `404` `NOT_FOUND` with `Content-Type: application/problem+json`, `type` `about:blank`, `title`, `status`, `detail`, `instance` and `requestId`;
  - with `X-Request-Id: abc-123`, the body's `requestId` equals the header;
  - an omitted field plus a negative number give two `errors` entries;
  - a string in the numeric field gives an `errors` entry naming that field;
  - malformed JSON gives `400` `VALIDATION_FAILED`;
  - a missing query parameter and a missing header each give an `errors` entry with their name;
  - a `text/plain` body gives `415` `UNSUPPORTED_MEDIA_TYPE`;
  - an unsupported method gives `405` with an `Allow` header;
  - `GONE` gives `410` `REQUEST_REJECTED`;
  - the unexpected exception gives `500` `INTERNAL_ERROR` with the constant detail and without the exception message;
  - the filter failure gives `500` problem+json whose `requestId` equals its header;
  - captured output shows a WARN line without a stack trace for a 4xx and an ERROR line with a stack trace for the 500
- [x] 3.3 Implement `ErrorCode` as in design decision 2 (status, detail template, `forStatus`), `ApiException(ErrorCode, Object... detailArgs)` and the `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` (one enrichment step, mapping table from design decision 3, `errors` extraction including Jackson 3's `MismatchedInputException` path, WARN/ERROR logging); set the fixed `en` locale and remove `spring.mvc.problemdetails.enabled` from `application.yml`
- [x] 3.4 Implement the custom `ErrorController` for `/error` (servlet error attributes, shared fallback mapping, original URI as `instance`, no exception message); make 3.1 and 3.2 pass

## 4. Catalog endpoints (tests first)

- [x] 4.1 Extend `DatabaseFixtures` with what the catalog tests need: an inactive bay, an inactive technician, and a deactivated qualification
- [x] 4.2 Write `CatalogIntegrationTest` on fixture data:
  - service types come back as `items` ordered by id, with `durationMinutes`;
  - with no dealerships, the list is `200` `{"items": []}`;
  - an `Asia/Ho_Chi_Minh` dealership shows `opens` `07:30` and `closes` `17:30`;
  - a dealership detail is `application/json` with top-level fields, and bays and technicians are ordered by id and limited to that dealership;
  - inactive bay and technician are listed with `active` `false`;
  - `qualifiedServiceTypeIds` is ascending and omits a deactivated qualification;
  - `GET /api/v1/dealerships/999` gives `404` `DEALERSHIP_NOT_FOUND` with `title` `Not Found` and `instance` `/api/v1/dealerships/999`;
  - `/abc` gives `400` with an `errors` entry for `id`, and `/0` gives `400` `VALIDATION_FAILED`;
  - `DELETE /api/v1/service-types` gives `405` with `Allow` containing `GET`
- [x] 4.3 Map the five catalog entities (`Dealership`, `ServiceType`, `ServiceBay`, `Technician`, `TechnicianSkill` with a composite id; ids as plain values, no associations) and confirm `contextLoads` passes with `ddl-auto: validate`
- [x] 4.4 Implement the repositories, the read-only catalog service (fixed number of queries for the dealership detail), the response records (`HH:mm` times), `ItemsResponse<T>` and the controller (`@PathVariable @Positive long id`, no `@Validated`); make 4.2 pass

## 5. OpenAPI

- [x] 5.1 Write a test that `GET /v3/api-docs` returns `200` and documents the three catalog paths, then open Swagger UI locally and check that the endpoints and their response schemas render

## 6. Documentation and manual checks

- [x] 6.1 Add an API section to `docs/SYSTEM_DESIGN.md`: success conventions, the error pipeline diagram, the error code table, request id flow; add the proposal's assumptions to its Assumptions section
- [x] 6.2 Update `AGENTS.md` and `openspec/config.yaml`: current state (catalog endpoints exist), no response envelope, `ItemsResponse<T>` for collections, failures thrown as `ApiException(ErrorCode)` with new codes added to the enum and the spec together, no `@Validated` on controllers, probe endpoints in the shared test configuration
- [x] 6.3 Run the application locally with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, call `GET /api/v1/dealerships` with `X-Request-Id: demo-1` and confirm the access log line is one JSON object with `requestId` `demo-1`; run once without the variable and confirm the console line shows `[demo-1]`; call an unknown dealership with cURL and keep the problem+json output
- [x] 6.4 Add the change to `docs/AI_LOG.md`: why there is no envelope, why `NOT_ACCEPTABLE` was dropped, the fixed locale, the error dispatch and MDC finding, the results of 6.3

## 7. Verification

- [x] 7.1 Run `./mvnw test` (with `JAVA_HOME` pointing at JDK 21+) and confirm every test passes, quoting the Surefire summary
- [x] 7.2 Map every scenario in `specs/api-conventions`, `specs/error-responses` and `specs/dealership-catalog`, plus the `observability` requirements "Request correlation id", "Access log" and "Structured logs on demand", to a passing test or a check from 6.3, and list gaps. Expected gaps: "Business error" (`NO_AVAILABILITY`) and the booking-named "Several invalid fields" scenario are shown only through probe equivalents until the booking change
- [x] 7.3 Run `openspec validate add-api-foundation --strict` and confirm it passes
