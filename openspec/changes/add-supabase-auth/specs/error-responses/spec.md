## MODIFIED Requirements

### Requirement: Problem details for every error
Every error response, whether raised by an endpoint, by request routing or parsing, by a filter or
by the servlet container, SHALL use RFC 9457 `application/problem+json` with `type` `about:blank`,
`title` set to the reason phrase of the HTTP status, `status`, `detail`, `instance` set to the
request path, a stable upper-snake-case `code`, and the `requestId` of the request. The `requestId`
MUST equal the `X-Request-Id` header of the same response. Clients MUST be able to rely on `code`
alone; `title` and `detail` are human-readable and may change. This includes the `401` and `403`
responses of the access-control capability.

#### Scenario: Business error
- **WHEN** a booking is rejected because no resources are free
- **THEN** the body is problem+json with `status` 409, `code` `NO_AVAILABILITY` and a `requestId`

#### Scenario: Unknown resource
- **WHEN** a client requests `GET /api/v1/dealerships/999` and no dealership 999 exists
- **THEN** the response is `404` with `Content-Type: application/problem+json`
- **AND** the body has `type` `about:blank`, `title` `Not Found`, `status` 404, `instance` `/api/v1/dealerships/999`, `code` `DEALERSHIP_NOT_FOUND`, a non-empty `detail` and a `requestId`

#### Scenario: Framework error
- **WHEN** an authenticated client calls an unknown path
- **THEN** the body is problem+json with `status` 404 and `code` `NOT_FOUND`

#### Scenario: Authentication error
- **WHEN** a request without a token calls an endpoint that requires one
- **THEN** the body is problem+json with `status` 401, `code` `UNAUTHENTICATED` and a `requestId` equal to its `X-Request-Id` header

#### Scenario: Request id in the body matches the header
- **WHEN** a request carrying `X-Request-Id: abc-123` fails
- **THEN** the response header `X-Request-Id` is `abc-123` and the body's `requestId` is `abc-123`

#### Scenario: Failure before any endpoint is reached
- **WHEN** an unexpected exception is thrown while the request is being processed outside an endpoint, for example in a filter
- **THEN** the response is `500` problem+json with `code` `INTERNAL_ERROR`
- **AND** its `requestId` equals its `X-Request-Id` header
