## Why

Every scored endpoint still to come (booking, availability, cancellation) needs the same plumbing:
one error format with a stable `code` and a `requestId`, a correlation id in every log line, and one
shape for successful responses. Building that inside the booking change would make the most
important change of the assessment much larger and leave the error layer tested only through
booking. The read-only catalog endpoints are the smallest real consumers of that plumbing. Clients
also need them to discover the dealership and service type ids that a booking request
(Scenario A core requirement 1) must contain. The catalog's JPA entities are reused by availability
and booking. The change serves maintainability (one error and response convention for all later
endpoints) and observability (correlation id, access log).

## What Changes

- Success responses: the body is the resource itself, with no `{success, data}` envelope. The HTTP
  status carries the outcome. Collections are returned as `{"items": [...]}` in a stable order.
- Global error handling: every error, including those raised by Spring MVC, the servlet container
  or a filter, is RFC 9457 `application/problem+json` with `type` `about:blank`, `title`, `status`,
  `detail`, `instance`, a stable `code` and the `requestId`:
  - invalid body fields, query parameters, path variables, headers or unreadable JSON: `400`
    `VALIDATION_FAILED` with `errors` entries;
  - unknown path `404` `NOT_FOUND`; wrong method `405` `METHOD_NOT_ALLOWED`; wrong `Content-Type`
    `415` `UNSUPPORTED_MEDIA_TYPE`; any other rejected request keeps its status with
    `REQUEST_REJECTED`;
  - anything unexpected: `500` `INTERNAL_ERROR` with a generic message.
  Spring Boot's built-in problem-details handler is switched off in favour of this one.
- Request correlation id and access log, as already specified in `observability`: `X-Request-Id`
  reused or generated, returned on every response, present in every log line and in the ECS JSON
  logs.
- Catalog: read-only JPA mappings for dealership, service type, service bay, technician and
  technician skill, and `GET /api/v1/service-types`, `GET /api/v1/dealerships`,
  `GET /api/v1/dealerships/{id}`.
- Test infrastructure: the shared integration test base starts a real HTTP server, so HTTP
  behaviour, including container error pages, is tested end to end on the Testcontainers database.

## Capabilities

### New Capabilities

- `api-conventions`: the shape of successful responses shared by every endpoint (resource bodies
  without an envelope, outcome carried by the HTTP status, collections as `items` in a stable order).

### Modified Capabilities

- `error-responses`: fixes the problem members (`type` `about:blank`, `title`, `instance`), requires
  `requestId` to equal the `X-Request-Id` response header, extends problem details to errors raised
  outside controllers, adds the framework codes `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` and
  `REQUEST_REJECTED`, and extends `VALIDATION_FAILED` from body fields
  to query parameters, path variables, headers and unreadable bodies.
- `dealership-catalog`: list bodies become `{"items": [...]}` ordered by id; `opens`/`closes` use
  `HH:mm`; `qualifiedServiceTypeIds` lists only active qualifications; a malformed dealership id is
  `400` `VALIDATION_FAILED`.

`observability` is implemented, not changed: this change builds its "Request correlation id",
"Access log" and "Structured logs on demand" requirements as written.

## Non-goals

- Booking, availability, cancellation and idempotency, and their error codes. The error code
  catalogue only receives the codes this change uses; later changes add theirs.
- Pagination, filtering or sorting parameters, caching headers, ETags.
- Writing catalog data (admin endpoints); catalog rows still come from migrations and demo data.
- Authentication, authorization, CORS.
- A documentation page per problem type, and localised error messages.
- Booking metrics, Dockerfile and docker compose.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- A technician whose qualification for a service type is inactive is not qualified for it, so that
  service type is not listed in `qualifiedServiceTypeIds`. Inactive bays and technicians are still
  listed, with `active: false`.
- Catalog lists are small (a handful of rows per dealership) and are returned unpaginated. The
  `items` wrapper lets pagination be added later without breaking clients.
- Clients branch on `code`, never on `title` or `detail`. Messages are English and may change.

## Impact

- New code: package `web`, split by function into `web.error` (error codes, exception, exception
  handler, error endpoint, problem builder), `web.filter` (request id filter, access log) and
  `web.dto` (collection wrapper); and package `catalog`, layered inside the feature into
  `controller`, `service`, `repository`, `entity` and `dto/response`.
- Changed: `application.yml` (Boot's problem-details handler off; request id in the console log
  pattern; fixed English locale; `hibernate.jdbc.time_zone` removed because it shifted `time`
  columns on non-UTC machines), `DatabaseIntegrationTest` (real HTTP server), `docs/SYSTEM_DESIGN.md` (API section and
  assumptions), `docs/AI_LOG.md`, `AGENTS.md` and `openspec/config.yaml` (current state and API
  conventions).
- APIs: three new `GET` endpoints; the error format of every path, including actuator and Swagger UI
  paths, becomes problem+json.
- No schema change, no new dependencies.
