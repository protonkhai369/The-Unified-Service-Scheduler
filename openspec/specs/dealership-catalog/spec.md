# Dealership Catalog Specification

## Purpose
Expose read-only reference data (dealerships, their opening hours, service bays, technicians
with their qualifications, and service types with durations) so API clients can discover valid
ids and understand why a booking was or was not possible.

## Requirements

### Requirement: List service types
The system SHALL return all service types from `GET /api/v1/service-types`, each with `id`,
`code`, `name` and `durationMinutes`.

#### Scenario: Service types with durations
- **WHEN** a client requests `GET /api/v1/service-types`
- **THEN** each item includes its duration in minutes

### Requirement: List dealerships
The system SHALL return all dealerships from `GET /api/v1/dealerships`, each with `id`, `name`,
`timeZone`, `opens` and `closes`.

#### Scenario: Dealership summary
- **WHEN** a client requests `GET /api/v1/dealerships`
- **THEN** each item includes its IANA time zone and daily opening and closing times

### Requirement: Dealership resources
The system SHALL return a dealership with its service bays and technicians from
`GET /api/v1/dealerships/{id}`, including each resource's `active` flag and, for technicians, the
ids of the service types they are qualified for. An unknown id MUST yield `404` with code
`DEALERSHIP_NOT_FOUND`.

#### Scenario: Technician qualifications visible
- **WHEN** a client requests an existing dealership
- **THEN** each technician lists `qualifiedServiceTypeIds`

#### Scenario: Unknown dealership
- **WHEN** a client requests a dealership id that does not exist
- **THEN** the response is `404` with code `DEALERSHIP_NOT_FOUND`
