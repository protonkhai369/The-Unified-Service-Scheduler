## Context

- The application is a skeleton: `ServiceSchedulerApplication`, `application.yml`, the V1 schema,
  demo data and schema integration tests. No entities, controllers or filters exist.
- `pom.xml` already has `spring-boot-starter-webmvc`, `-validation`, `-data-jpa`, `-actuator`,
  springdoc 3.1.1 and the Micrometer Prometheus registry. Spring Boot 4.1.2 means Spring Framework 7
  and Jackson 3 (`tools.jackson.*`, whose exceptions are unchecked).
- `application.yml` sets `spring.mvc.problemdetails.enabled: true`. This registers Boot's handler,
  which turns Spring MVC exceptions into plain `ProblemDetail` bodies without `code` or `requestId`.
  Exceptions thrown outside Spring MVC still reach Boot's `BasicErrorController`, which renders its
  own JSON format.
- The catalog tables (`dealership`, `service_type`, `service_bay`, `technician`,
  `technician_skill`) exist in schema `scheduler` and are filled by demo data locally and on
  Supabase, and by `DatabaseFixtures` in tests.
- `DatabaseIntegrationTest` runs one shared Spring context with the default mock web environment.
  The PostgreSQL container is a bean of `TestcontainersConfiguration`, so every distinct test
  context would start its own container.

## Goals / Non-Goals

**Goals:**

- One error pipeline. Every failure, whether thrown by an endpoint, by Spring MVC, by a filter or by
  the container, ends as the same problem+json with `code` and `requestId`.
- One success convention that later changes follow without re-deciding it.
- Each request is traceable through its `X-Request-Id`: on the response, in every log line, in the
  error body.
- Catalog endpoints that prove the pipeline over real HTTP and give later changes their entities.

**Non-Goals:** booking, availability, idempotency, pagination, catalog writes, security, Docker
(see proposal).

## Decisions

### 1. No response envelope; one collection wrapper

Every endpoint returns its own response record (`DealershipResponse`, `ServiceTypeResponse`, ...)
as the body. The status code and headers come from the controller's return type or a
`ResponseEntity`. The only generic type is `ItemsResponse<T>(List<T> items)` for collections.

Alternatives considered:
- An `ApiResponse<T> {success, code, message, data}` envelope: rejected for three reasons.
  - It would be a second error format beside the problem+json that `error-responses` requires.
  - Its usual "always 200, check `success`" form hides failures from `http_server_requests_seconds`
    (tagged by status), from proxies and from status-based retries.
  - Its name collides with springdoc's `@ApiResponse` annotation, which controllers use to
    document responses.
- Bare JSON arrays for collections: rejected. Adding pagination metadata later would break every
  client. `{"items": [...]}` leaves room for it.

### 2. Error catalogue: the `ErrorCode` enum

Every error code is a constant of one enum, `ErrorCode`, in package `web.error`. The enum is the only place
where a code, its HTTP status and its default message are defined. The fallback rule for errors
without a specific code also lives there:

```java
public enum ErrorCode {

    // Request and framework errors
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No endpoint matches this path."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported for this path."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This content type is not supported."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request was rejected."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),

    // Catalog
    DEALERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "Dealership %d does not exist.");

    private final HttpStatus status;
    private final String detailTemplate;

    // constructor, status(), and detail(Object... args) = detailTemplate.formatted(args)

    /** The code for an error without a more specific one; the response keeps the original status. */
    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }
}
```

Application services fail with one unchecked exception that takes the constant and the template's
arguments:

```java
throw new ApiException(ErrorCode.DEALERSHIP_NOT_FOUND, id);   // detail: "Dealership 999 does not exist."
```

Rules:
- The wire `code` is the constant's `name()`. There is no separate string or number that could drift
  from it, so renaming a constant is a breaking API change.
- `title` is not stored in the enum: the spec makes it the reason phrase of the status.
- Detail templates contain only values the client sent, such as ids. They never contain internal
  state.
- `REQUEST_REJECTED` and `INTERNAL_ERROR` are fallbacks. When `forStatus` picks them, the response
  keeps the status that actually occurred (for example 410 or 503), not the enum's default status.
- Each later change adds its constants together with the spec text that introduces them (booking
  adds `NO_AVAILABILITY`, `SLOT_CONTENTION`, ...). A unit test fails when a constant appears in no
  spec under `openspec/`, so the enum and the specs cannot drift apart.
- The pure scheduling domain (a later change) never throws `ApiException`. It returns results, and
  the Spring service that calls it translates them into codes. This keeps HTTP types out of the
  domain package, as AGENTS.md requires.

Alternatives considered:
- Numeric codes (`1001`, `1002`, ...) in the enum: the spec defines upper-snake-case string codes,
  which are self-describing in logs and client code and need no lookup table.
- One exception class per error (`DealershipNotFoundException`, ...): many classes, plus a mapping
  table that can drift from the specs.
- `@ResponseStatus` on exceptions: carries no `code`, no `requestId`, and no problem+json body.

### 3. One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`

The base class already converts every Spring MVC exception into a `ProblemDetail` with the right
status and headers, including `Allow` on a 405. The advice overrides the single method that all
base handlers pass through (`handleExceptionInternal`) and adds its own handlers for `ApiException`
and `Exception`. Every path then runs one enrichment step:
- `type` stays `about:blank`;
- `title` becomes the reason phrase of the status;
- `instance` is the request path;
- `code` comes from the table below;
- `requestId` is read from the request attribute set by the filter (decision 5).

Once this advice exists, Boot's own handler backs off, because it is conditional on there being no
other `ResponseEntityExceptionHandler` bean. `spring.mvc.problemdetails.enabled` is removed from
`application.yml` so it does not suggest otherwise.

| Exception | Status | `code` | `errors` |
| --- | --- | --- | --- |
| `ApiException` | from its `ErrorCode` | its `ErrorCode` | none |
| `MethodArgumentNotValidException` (`@Valid` body) | 400 | `VALIDATION_FAILED` | one per field error, by property path |
| `HandlerMethodValidationException` (constraints on `@PathVariable`, `@RequestParam`, `@RequestHeader`) | 400 | `VALIDATION_FAILED` | one per parameter, by its name |
| `MethodArgumentTypeMismatchException` | 400 | `VALIDATION_FAILED` | the parameter name |
| `MissingServletRequestParameterException`, `MissingRequestHeaderException` | 400 | `VALIDATION_FAILED` | the parameter or header name |
| `HttpMessageNotReadableException` | 400 | `VALIDATION_FAILED` | the field from Jackson's path when the cause is a Jackson `MismatchedInputException`, otherwise empty |
| `NoResourceFoundException` | 404 | `NOT_FOUND` | none |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` | none |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` | none |
| any other exception the base class handles | its status | `REQUEST_REJECTED` (4xx) or `INTERNAL_ERROR` (5xx) | none |
| any other `Exception` | 500 | `INTERNAL_ERROR` | none |

The fallback rows use `ErrorCode.forStatus`, which the error endpoint (decision 4) shares.

Further rules:
- Path variables and query parameters are validated by Spring MVC's built-in method validation,
  using constraint annotations on the parameters, for example `@PathVariable @Positive long id`.
  Controllers MUST NOT carry `@Validated`. That annotation switches to AOP method validation, whose
  `ConstraintViolationException` would fall through to a 500.
- Bean Validation messages are interpolated in the request's locale. A fixed English locale
  (`spring.web.locale: en`, `spring.web.locale-resolver: fixed`) keeps messages from changing with
  `Accept-Language` or the host machine's locale. Tests assert `field`, not message text.
- `detail` comes from the enum's template for `ApiException`, `NOT_FOUND` and `INTERNAL_ERROR`,
  and from Spring otherwise (for example "Method 'DELETE' is not supported."). The enum is used for
  `NOT_FOUND` because Spring's own 404 detail ("No static resource ...") describes server internals.
  It is used for `INTERNAL_ERROR` because an exception message may contain SQL or constraint names;
  those appear only in the ERROR log line with its stack trace.

### 4. Errors outside Spring MVC: our own `ErrorController`

An exception thrown in a filter, or a request the container rejects, never reaches the advice. The
container forwards it to `/error`, where Boot's `BasicErrorController` would answer with its own
format (`timestamp`, `error`, `path`). A custom `ErrorController` replaces it; Boot's controller is
conditional on there being no other `ErrorController`. The custom controller:
- reads the servlet error attributes (status code, original request URI, exception);
- picks the code with `ErrorCode.forStatus` (404 `NOT_FOUND`, 405 `METHOD_NOT_ALLOWED`, other
  4xx `REQUEST_REJECTED`, 5xx `INTERNAL_ERROR`);
- sets `instance` to the original URI and never exposes the exception message;
- logs 5xx with the stack trace.

Alternative considered: customising Boot's `ErrorAttributes`. It keeps Boot's response format and
`application/json` content type, so it cannot produce problem+json.

### 5. Request id filter and access log

A `OncePerRequestFilter` runs at the highest precedence, so even failures in later filters have an
id. It works as follows:
- It accepts the caller's `X-Request-Id` when it matches `[A-Za-z0-9._-]{1,64}` and otherwise
  generates a random UUID. This validation is a pure function and is unit-tested.
- It stores the id as a request attribute and in the MDC under `requestId`, and sets the response
  header before calling the chain.
- It removes the MDC entry in `finally`.
- It also runs on ERROR dispatches (`shouldNotFilterErrorDispatch()` returns `false`). There it
  reuses the id already stored in the request attribute instead of generating a new one. The MDC
  entry of the original dispatch is cleared before the container forwards to `/error`, so this is
  what puts the same id on the `/error` response and its log lines.

Access log:
- After the chain completes, the filter writes one INFO line on logger
  `com.keyloop.scheduler.web.AccessLog`, for example `GET /api/v1/dealerships -> 200 (12 ms)`.
- Only paths under `/api/` are logged. Kubernetes-style health probes every few seconds would
  otherwise drown the log.
- The line contains the path without the query string, which may carry personal data in later
  endpoints.

Log output:
- `logging.pattern.correlation: "[%X{requestId:-}] "` puts the id into Boot's default console line.
- With `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, Boot's ECS formatter writes MDC entries as JSON
  fields, so `requestId` appears without further code. This is verified manually (task 6.3),
  because reconfiguring logging inside the shared test context would affect every other test's
  output capture.

Concurrency: the MDC is thread-local and the filter always clears it, so a pooled Tomcat thread
never carries one request's id into the next. Nothing in this change processes requests
asynchronously.

### 6. Catalog read model

Package `catalog` is layered inside the feature (the project stays packaged by feature at the top
level):
- `controller`: `ServiceTypeController`, `DealershipController`.
- `service`: `ServiceTypeService`, `DealershipService`.
- `repository`: one Spring Data repository per entity.
- `entity`: five JPA entities mapped to the V1 tables: `Dealership`, `ServiceType`, `ServiceBay`,
  `Technician`, and `TechnicianSkill` with a composite id.
- `dto/response`: the response records. `dto/request` arrives with the first request body
  (booking).

Mapping choices:
- Columns `GENERATED ALWAYS AS IDENTITY` map to `@GeneratedValue(strategy = IDENTITY)`.
- `time` maps to `LocalTime`. Found while implementing: the skeleton's
  `hibernate.jdbc.time_zone: UTC` made Hibernate read `time` values as UTC on 1970-01-01 and convert
  them to the JVM zone. On a machine in `Asia/Ho_Chi_Minh` (UTC+8 in 1970), `08:00` came back as
  `16:00`. The setting is removed. `Instant` maps to `timestamptz` without it. The booking change
  must still prove an `Instant` round trip on a non-UTC JVM.
- The IANA zone is kept as a `String`.
- Following the project rule "aggregates reference each other by id", `ServiceBay`, `Technician`
  and `TechnicianSkill` hold `dealershipId`, `technicianId` and `serviceTypeId` as plain values,
  not `@ManyToOne` associations. Queries stay explicit and lazy-loading pitfalls cannot occur.
- `ddl-auto: validate` makes any mapping drift from V1 fail at startup (`contextLoads`).

`GET /api/v1/dealerships/{id}` runs in one `@Transactional(readOnly = true)` service method with a
fixed number of queries, whatever the number of technicians:
1. the dealership by id (`DEALERSHIP_NOT_FOUND` if absent);
2. its bays ordered by id;
3. its technicians ordered by id;
4. the active skills of those technicians in a single `IN` query.

Under `READ COMMITTED`, each statement sees the latest committed data, so a qualification changed
mid-request could be half-visible. That is acceptable: catalog rows change only through migrations.

Response records live in `catalog.dto.response`. `opens` and `closes` are pinned to `HH:mm` with an explicit
Jackson format, rather than relying on Jackson's default `LocalTime` output.

### 7. Test approach

- `DatabaseIntegrationTest` switches to `webEnvironment = RANDOM_PORT`. All integration tests keep
  one context and one container, and HTTP tests talk to a real Tomcat. A real server is required:
  MockMvc never performs the container's error dispatch to `/error`, so decision 4 could not be
  tested with it.
- HTTP tests use Spring Framework 7's `RestTestClient`, bound to the random port. It ships in
  `spring-test`, so no new dependency is needed.
- Test-only probe endpoints (under `/api/test-probe/`, so the access log covers them too) and a
  filter that throws on one probe path
  exercise behaviour the catalog cannot trigger: an invalid JSON body, a wrong-type field, a
  missing query parameter or header, an unsupported content type, an unexpected exception, and a
  failure in a filter. They are declared in a `@TestConfiguration` imported by
  `DatabaseIntegrationTest` itself. Importing it in only one test class would create a second
  context and a second container.
- Pure JUnit tests cover the request id rule and the status-to-code fallback.
- `OutputCaptureExtension` asserts the access log line and the request id in log lines.

## Observability

- Log events added:
  - one INFO access log line per `/api/**` request;
  - one WARN line per 4xx problem (`code`, status, path; no stack trace);
  - one ERROR line with stack trace per 5xx.
  - Every line carries `requestId`.
- Metrics: no custom metrics. Actuator's `http_server_requests_seconds` now records the real error
  statuses, and its `outcome` tag (`CLIENT_ERROR`, `SERVER_ERROR`) follows them. This is the reason
  for rejecting an always-200 envelope.

## Risks / Trade-offs

- [Jackson 3 exception types differ from Jackson 2 (`tools.jackson.databind.exc.*`, unchecked)]
  → The handler inspects the cause of `HttpMessageNotReadableException` for Jackson 3's
  `MismatchedInputException`. Wrong-type and malformed-body probe tests prove the field name is
  extracted.
- [The error dispatch runs after the original filter chain has unwound and cleared the MDC]
  → The filter also handles ERROR dispatches and reuses the stored id. The failing-filter probe test
  asserts the header and body ids are equal.
- [Someone adds `@Validated` to a controller and path-variable violations become 500s]
  → Recorded as a rule in AGENTS.md. `GET /api/v1/dealerships/0` is tested to return 400.
- [Switching the shared test base to `RANDOM_PORT` starts Tomcat for schema-only tests too]
  → The cost is paid once per run, because the context is shared.
- [springdoc may add the advice's generic error responses to every documented operation]
  → Acceptable, since every operation can return a problem. If it proves noisy, set
  `springdoc.override-with-generic-response: false`.
- [A client whose `Accept` excludes JSON may receive a problem body it did not ask for]
  → Errors are always problem+json by spec. Such responses fall under `REQUEST_REJECTED` and are not
  specified further.

## Migration Plan

There is no schema change. Deploying means restarting the application, and rolling back means
redeploying the previous build. No clients exist yet. The only visible change for existing paths is
that framework errors (for example an unknown path) gain `code` and `requestId`.

## Open Questions

None blocking.
