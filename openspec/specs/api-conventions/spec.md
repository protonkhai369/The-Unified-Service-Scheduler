# API Conventions Specification

## Purpose
Give every endpoint one shape for successful responses, so clients parse resources and collections
the same way everywhere and read success or failure from the HTTP status alone. Failures follow the
`error-responses` specification.

## Requirements

### Requirement: Resource bodies without an envelope
A successful response SHALL be `application/json` whose body is the resource itself, with no
wrapper object carrying a success flag, code or message. The outcome MUST be expressed by the HTTP
status alone: `2xx` for success, and `4xx` or `5xx` with a problem+json body (see `error-responses`)
for every failure.

#### Scenario: Single resource
- **WHEN** a client requests an existing dealership
- **THEN** the response is `200` with `Content-Type: application/json`
- **AND** the dealership's `id`, `name` and other fields are members of the top-level body object

#### Scenario: Failure is never a 200
- **WHEN** a client requests a dealership that does not exist
- **THEN** the response status is `404` and the body is `application/problem+json`, not a `200` with an error flag

### Requirement: Collections wrapped in items
A collection response SHALL be an object whose `items` member holds the elements as an array,
ordered by ascending id unless the endpoint's specification states another order. An empty
collection MUST be returned as `200` with `{"items": []}`.

#### Scenario: Non-empty collection
- **WHEN** a client lists service types
- **THEN** the body is an object whose `items` array holds every service type ordered by ascending id

#### Scenario: Empty collection
- **WHEN** a client lists dealerships and none exist
- **THEN** the response is `200` with body `{"items": []}`
