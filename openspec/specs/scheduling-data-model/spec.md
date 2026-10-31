# Scheduling Data Model Specification

## Purpose
Define the persisted entities of Scenario A (dealerships, service types, service bays,
technicians and their qualifications, customers, vehicles and appointments) and the integrity
rules PostgreSQL itself guarantees for them, so that no service bay, technician or vehicle is
ever double-booked regardless of request timing. Supports core requirements 2 (bay and qualified
technician free for the whole duration) and 3 (confirmed appointment record).

## Requirements

### Requirement: Reference data integrity
The database SHALL store dealerships (name, IANA time zone, daily opening and closing time), service
types (unique code, duration in minutes), service bays and technicians (each in one dealership, with
an active flag), technician qualifications per service type, customers (unique email) and vehicles
(unique VIN, owned by one customer), and MUST reject rows that break these rules.

#### Scenario: Malformed VIN rejected
- **WHEN** a vehicle is inserted with a VIN that is not 17 characters of `A-H J-N P R-Z 0-9` (for example one containing the letter `I`)
- **THEN** the insert fails with a check violation (SQLSTATE `23514`)

#### Scenario: Duplicate VIN rejected
- **WHEN** a second vehicle is inserted with an existing VIN
- **THEN** the insert fails with a unique violation (SQLSTATE `23505`)

#### Scenario: Non-positive service duration rejected
- **WHEN** a service type is inserted with a duration of 0 minutes
- **THEN** the insert fails with a check violation (SQLSTATE `23514`)

#### Scenario: Closing time before opening time rejected
- **WHEN** a dealership is inserted that opens at 18:00 and closes at 08:00
- **THEN** the insert fails with a check violation (SQLSTATE `23514`)

### Requirement: Appointment record
The database SHALL persist each appointment with its dealership, customer, vehicle, service type,
service bay, technician, start and end instants, status (`CONFIRMED` or `CANCELLED`), idempotency
key, request fingerprint and creation/update timestamps. It MUST reject an appointment whose end is
not after its start, whose status is unknown, or that references a row that does not exist.

#### Scenario: Valid appointment stored
- **WHEN** an appointment referencing existing rows is inserted with start 10:00 and end 11:30
- **THEN** it is stored with status `CONFIRMED` and its time range reads back as the same instants

#### Scenario: Empty or inverted time range rejected
- **WHEN** an appointment is inserted whose end equals or precedes its start
- **THEN** the insert fails with a check violation (SQLSTATE `23514`)

#### Scenario: Unknown status rejected
- **WHEN** an appointment is inserted with status `PENDING`
- **THEN** the insert fails with a check violation (SQLSTATE `23514`)

#### Scenario: Dangling reference rejected
- **WHEN** an appointment references a customer id that does not exist
- **THEN** the insert fails with a foreign key violation (SQLSTATE `23503`)

### Requirement: No overlapping bookings of a service bay
The database MUST reject a `CONFIRMED` appointment whose time range overlaps another `CONFIRMED`
appointment for the same service bay, with an exclusion violation (SQLSTATE `23P01`) on constraint
`appointment_no_bay_overlap`. Ranges are half-open, so one appointment may start exactly when
another ends. `CANCELLED` appointments MUST NOT block.

#### Scenario: Partial overlap rejected
- **WHEN** a bay is booked 10:00-11:00 and another confirmed appointment for that bay is inserted for 10:30-11:30
- **THEN** the insert fails with SQLSTATE `23P01` on `appointment_no_bay_overlap`

#### Scenario: Back-to-back allowed
- **WHEN** a bay is booked 10:00-11:00 and another appointment for that bay is inserted for 11:00-12:00
- **THEN** the insert succeeds

#### Scenario: Cancelled appointment does not block
- **WHEN** the 10:00-11:00 appointment is set to `CANCELLED` and a new one is inserted for 10:00-11:00 on that bay
- **THEN** the insert succeeds

#### Scenario: Overlap detected across time-zone offsets
- **WHEN** a bay is booked 10:00-11:00 at `+07:00` and an appointment for that bay is inserted for 03:30-04:30 at `Z` (the same as 10:30-11:30 at `+07:00`)
- **THEN** the insert fails with SQLSTATE `23P01`, because ranges are compared as instants

#### Scenario: Concurrent conflicting inserts
- **WHEN** two transactions insert overlapping confirmed appointments for the same bay at the same time
- **THEN** exactly one commits and the other fails with SQLSTATE `23P01` once the first commits

### Requirement: No overlapping bookings of a technician
The database MUST reject a `CONFIRMED` appointment whose time range overlaps another `CONFIRMED`
appointment for the same technician, with SQLSTATE `23P01` on constraint
`appointment_no_technician_overlap`, using the same half-open ranges and the same rule that
`CANCELLED` appointments do not block.

#### Scenario: Same technician on another bay rejected
- **WHEN** a technician is booked 10:00-11:00 on bay 1 and an overlapping appointment for that technician is inserted on bay 2
- **THEN** the insert fails with SQLSTATE `23P01` on `appointment_no_technician_overlap`

#### Scenario: Different technicians at the same time allowed
- **WHEN** two appointments for the same period use different bays and different technicians
- **THEN** both inserts succeed

### Requirement: One booking per vehicle at a time
The database MUST reject a `CONFIRMED` appointment whose time range overlaps another `CONFIRMED`
appointment for the same vehicle, with SQLSTATE `23P01` on constraint
`appointment_no_vehicle_overlap`, even when the bay and technician differ.

#### Scenario: Duplicate booking of a vehicle rejected
- **WHEN** a vehicle is booked 10:00-11:00 and another overlapping appointment for that vehicle is inserted with a different bay and technician
- **THEN** the insert fails with SQLSTATE `23P01` on `appointment_no_vehicle_overlap`

#### Scenario: Two vehicles of one customer at the same time allowed
- **WHEN** a customer's two different vehicles are booked for the same period on different bays and technicians
- **THEN** both inserts succeed

### Requirement: Qualified technician from the same dealership
The database MUST reject an appointment whose technician is not qualified for its service type, or
whose service bay or technician belongs to a different dealership than the appointment, with a
foreign key violation (SQLSTATE `23503`). A qualification still referenced by an appointment MUST
NOT be deletable.

#### Scenario: Unqualified technician rejected
- **WHEN** an appointment assigns a technician who has no qualification for its service type
- **THEN** the insert fails with SQLSTATE `23503`

#### Scenario: Bay from another dealership rejected
- **WHEN** an appointment for dealership 1 references a service bay of dealership 2
- **THEN** the insert fails with SQLSTATE `23503`

#### Scenario: Technician from another dealership rejected
- **WHEN** an appointment for dealership 1 references a technician of dealership 2
- **THEN** the insert fails with SQLSTATE `23503`

#### Scenario: Used qualification cannot be deleted
- **WHEN** a qualification referenced by an appointment is deleted
- **THEN** the delete fails with SQLSTATE `23503`

### Requirement: Idempotency key storage
Every appointment MUST store an idempotency key matching `[A-Za-z0-9._:-]{1,100}` that is unique
across all appointments, and a request fingerprint of 64 lowercase hexadecimal characters. A second
appointment with an existing key MUST be rejected with SQLSTATE `23505` on constraint
`appointment_idempotency_key_unique`.

#### Scenario: Duplicate key rejected
- **WHEN** an appointment is inserted with an idempotency key that another appointment already has
- **THEN** the insert fails with SQLSTATE `23505` on `appointment_idempotency_key_unique`

#### Scenario: Missing or malformed key rejected
- **WHEN** an appointment is inserted without an idempotency key, or with a key containing a space
- **THEN** the insert fails with a not-null (SQLSTATE `23502`) or check (SQLSTATE `23514`) violation

#### Scenario: Concurrent inserts with the same key
- **WHEN** two transactions insert appointments with the same idempotency key at the same time
- **THEN** exactly one commits and the other fails once the first commits, with SQLSTATE `23505` or, if the appointments also overlap, `23P01`
