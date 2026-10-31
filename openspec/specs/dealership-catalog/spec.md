# Dealership Catalog Specification

## Purpose
Expose read-only reference data (dealerships, their opening hours, service bays, technicians
with their qualifications, and service types with durations) so API clients can discover valid
ids and understand why a booking was or was not possible.

## Requirements

### Requirement: List service types
The system SHALL return all service types from `GET /api/v1/service-types` as
`{"items": [...]}` ordered by ascending id, each item with `id`, `code`, `name` and
`durationMinutes`.

#### Scenario: Service types with durations
- **WHEN** a client requests `GET /api/v1/service-types`
- **THEN** the response is `200` and each item includes its duration in minutes
- **AND** the items are ordered by ascending id

### Requirement: List dealerships
The system SHALL return all dealerships from `GET /api/v1/dealerships` as `{"items": [...]}`
ordered by ascending id, each item with `id`, `name`, `timeZone`, `opens` and `closes`. `timeZone`
MUST be the IANA zone id; `opens` and `closes` MUST be local times in that zone formatted `HH:mm`.

#### Scenario: Dealership summary
- **WHEN** a client requests `GET /api/v1/dealerships`
- **THEN** each item includes its IANA time zone and daily opening and closing times

#### Scenario: Opening hours format
- **WHEN** a dealership in `Asia/Ho_Chi_Minh` opens at 07:30 and closes at 17:30
- **THEN** its item has `timeZone` `Asia/Ho_Chi_Minh`, `opens` `07:30` and `closes` `17:30`

### Requirement: Dealership resources
The system SHALL return a dealership from `GET /api/v1/dealerships/{id}` with the fields of the
dealership list plus `serviceBays` (each with `id`, `name`, `active`) and `technicians` (each with
`id`, `name`, `active`, `qualifiedServiceTypeIds`), both ordered by ascending id and limited to that
dealership. Inactive bays and technicians MUST be included with `active` `false`.
`qualifiedServiceTypeIds` MUST list, in ascending order, the ids of the service types for which the
technician holds an active qualification. An unknown id MUST yield `404` with code
`DEALERSHIP_NOT_FOUND`; an id that is not a positive integer MUST yield `400` with code
`VALIDATION_FAILED`.

#### Scenario: Technician qualifications visible
- **WHEN** a client requests an existing dealership
- **THEN** each technician lists `qualifiedServiceTypeIds`

#### Scenario: Inactive qualification not listed
- **WHEN** a technician's qualification for a service type has been deactivated
- **THEN** that service type's id is absent from the technician's `qualifiedServiceTypeIds`

#### Scenario: Inactive resources still listed
- **WHEN** a dealership has an inactive technician and an inactive bay
- **THEN** both appear in the response with `active` `false`

#### Scenario: Only the dealership's own resources
- **WHEN** two dealerships each have bays and technicians and a client requests one of them
- **THEN** the response lists only that dealership's bays and technicians

#### Scenario: Unknown dealership
- **WHEN** a client requests a dealership id that does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`

#### Scenario: Id that is not a positive integer
- **WHEN** a client requests `GET /api/v1/dealerships/abc` or `GET /api/v1/dealerships/0`
- **THEN** the response is `400` with code `VALIDATION_FAILED`
