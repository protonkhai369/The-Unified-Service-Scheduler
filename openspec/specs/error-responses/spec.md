# Error Responses Specification

## Purpose
Give API clients one predictable error format so they can branch on a stable machine-readable
code instead of parsing messages, and give operators a request id that links an error response
to the server log lines that explain it.

## Requirements

### Requirement: Problem details for every error
Every error response, whether raised by an endpoint, by request routing or parsing, by a filter or
by the servlet container, SHALL use RFC 9457 `application/problem+json` with `type` `about:blank`,
`title` set to the reason phrase of the HTTP status, `status`, `detail`, `instance` set to the
request path, a stable upper-snake-case `code`, and the `requestId` of the request. The `requestId`
MUST equal the `X-Request-Id` header of the same response. Clients MUST be able to rely on `code`
alone; `title` and `detail` are human-readable and may change.

#### Scenario: Business error
- **WHEN** a booking is rejected because no resources are free
- **THEN** the body is problem+json with `status` 409, `code` `NO_AVAILABILITY` and a `requestId`

#### Scenario: Unknown resource
- **WHEN** a client requests `GET /api/v1/dealerships/999` and no dealership 999 exists
- **THEN** the response is `404` with `Content-Type: application/problem+json`
- **AND** the body has `type` `about:blank`, `title` `Not Found`, `status` 404, `instance` `/api/v1/dealerships/999`, `code` `DEALERSHIP_NOT_FOUND`, a non-empty `detail` and a `requestId`

#### Scenario: Framework error
- **WHEN** a client calls an unknown path
- **THEN** the body is problem+json with `status` 404 and `code` `NOT_FOUND`

#### Scenario: Request id in the body matches the header
- **WHEN** a request carrying `X-Request-Id: abc-123` fails
- **THEN** the response header `X-Request-Id` is `abc-123` and the body's `requestId` is `abc-123`

#### Scenario: Failure before any endpoint is reached
- **WHEN** an unexpected exception is thrown while the request is being processed outside an endpoint, for example in a filter
- **THEN** the response is `500` problem+json with `code` `INTERNAL_ERROR`
- **AND** its `requestId` equals its `X-Request-Id` header

### Requirement: Field-level validation errors
For invalid request input the system SHALL respond `400` with code `VALIDATION_FAILED` and an
`errors` array of `{field, message}` entries, one per invalid input. Body fields MUST be named by
their JSON property path; query parameters, path variables and headers MUST be named as they are
sent. A body that is not well-formed JSON MUST also yield `400` with code `VALIDATION_FAILED`; its
`errors` array is empty when no single field can be blamed.

#### Scenario: Several invalid fields
- **WHEN** a booking request omits `vehicleId` and sends `dealershipId` as `-1`
- **THEN** `errors` contains one entry for `vehicleId` and one for `dealershipId`

#### Scenario: Value of the wrong type in the body
- **WHEN** a request body sends a string such as `"abc"` for a numeric field
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry naming that field

#### Scenario: Body that is not JSON
- **WHEN** a request body is not well-formed JSON
- **THEN** the response is `400` with code `VALIDATION_FAILED`

#### Scenario: Malformed path variable
- **WHEN** a client requests `GET /api/v1/dealerships/abc`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `id`

#### Scenario: Missing query parameter or header
- **WHEN** a required query parameter or a required header is missing
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry naming the missing parameter or header

### Requirement: No internal details leaked
Unexpected server errors MUST respond `500` with code `INTERNAL_ERROR` and a generic message; the
stack trace SHALL only be written to the server log.

#### Scenario: Unexpected exception
- **WHEN** an unhandled exception occurs while processing a request
- **THEN** the response is `500` with code `INTERNAL_ERROR` and no exception message or stack trace

### Requirement: Framework error codes
Request errors detected before an endpoint runs SHALL use these codes: `404` `NOT_FOUND` when no
endpoint matches the path; `405` `METHOD_NOT_ALLOWED` when the path exists but not for the method,
with an `Allow` header listing the supported methods; `415` `UNSUPPORTED_MEDIA_TYPE` when the
request body's content type is not supported. Any other `4xx` without a more specific code MUST keep
its status and use `REQUEST_REJECTED`; any other `5xx` MUST keep its status and use `INTERNAL_ERROR`.

#### Scenario: Wrong method
- **WHEN** a client sends `DELETE /api/v1/service-types`
- **THEN** the response is `405` with code `METHOD_NOT_ALLOWED` and an `Allow` header containing `GET`

#### Scenario: Unsupported content type
- **WHEN** a client sends a request body as `text/plain` to an endpoint that accepts only JSON
- **THEN** the response is `415` with code `UNSUPPORTED_MEDIA_TYPE`

#### Scenario: Other client error
- **WHEN** a request is rejected with a `4xx` status that has no specific code
- **THEN** the response keeps that status and uses code `REQUEST_REJECTED`
