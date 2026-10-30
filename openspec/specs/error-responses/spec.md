# Error Responses Specification

## Purpose
Give API clients one predictable error format so they can branch on a stable machine-readable
code instead of parsing messages, and give operators a request id that links an error response
to the server log lines that explain it.

## Requirements

### Requirement: Problem details for every error
Every error response SHALL use RFC 9457 `application/problem+json` with `status`, `title`,
`detail`, a stable upper-snake-case `code`, and the `requestId` of the request.

#### Scenario: Business error
- **WHEN** a booking is rejected because no resources are free
- **THEN** the body is problem+json with `status` 409, `code` `NO_AVAILABILITY` and a `requestId`

#### Scenario: Framework error
- **WHEN** a client calls an unknown path
- **THEN** the body is problem+json with `status` 404 and `code` `NOT_FOUND`

### Requirement: Field-level validation errors
For request body validation failures the system SHALL respond `400` with code
`VALIDATION_FAILED` and an `errors` array of `{field, message}` entries.

#### Scenario: Several invalid fields
- **WHEN** a booking request omits `vehicleId` and sends `dealershipId` as `-1`
- **THEN** `errors` contains one entry for `vehicleId` and one for `dealershipId`

### Requirement: No internal details leaked
Unexpected server errors MUST respond `500` with code `INTERNAL_ERROR` and a generic message; the
stack trace SHALL only be written to the server log.

#### Scenario: Unexpected exception
- **WHEN** an unhandled exception occurs while processing a request
- **THEN** the response is `500` with code `INTERNAL_ERROR` and no exception message or stack trace
