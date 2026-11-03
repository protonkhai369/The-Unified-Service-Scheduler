## ADDED Requirements

### Requirement: Caller profile
`GET /api/v1/me` SHALL answer `200` to any request with a valid token, whatever its role. The body
SHALL hold:
- `userId`: the token's subject;
- `role`: `CUSTOMER`, `ADVISOR`, `ADMIN`, or `null` when the token carries no known role;
- `customer`: `{id, name}` of the customer a `CUSTOMER` is linked to;
- `dealership`: `{id, name}` of the dealership an `ADVISOR` works at.

`customer` and `dealership` SHALL be `null` when they do not apply to the role, or when the linked id
matches no record. A request without a valid token receives `401` with code `UNAUTHENTICATED`.

#### Scenario: Customer profile
- **WHEN** a customer token with `customer_id` 2 calls `GET /api/v1/me`, and customer 2 is "Le Minh Duc"
- **THEN** the response is `200` with `role` `CUSTOMER`, `customer` `{"id": 2, "name": "Le Minh Duc"}` and `dealership` `null`

#### Scenario: Advisor profile
- **WHEN** an advisor token with `dealership_id` 2 calls `GET /api/v1/me`, and dealership 2 is "Saigon Auto Centre"
- **THEN** the response is `200` with `role` `ADVISOR`, `dealership` `{"id": 2, "name": "Saigon Auto Centre"}` and `customer` `null`

#### Scenario: Admin profile
- **WHEN** an admin token calls `GET /api/v1/me`
- **THEN** the response is `200` with `role` `ADMIN`, and `customer` and `dealership` both `null`

#### Scenario: Token without a role
- **WHEN** a valid token without a known role calls `GET /api/v1/me`
- **THEN** the response is `200` with `role` `null`, so a client can explain why the account cannot book

#### Scenario: Link to a missing record
- **WHEN** a customer token with `customer_id` 999 calls `GET /api/v1/me`, and no customer 999 exists
- **THEN** the response is `200` with `role` `CUSTOMER` and `customer` `null`

#### Scenario: Profile without a token
- **WHEN** `GET /api/v1/me` is sent without a token
- **THEN** the response is `401` with code `UNAUTHENTICATED`

### Requirement: Caller's vehicles
`GET /api/v1/me/vehicles` SHALL return the vehicles of the caller's customer as `{"items": [...]}`,
ordered by id. Each item SHALL have `id`, `vin`, `make`, `model` and `modelYear`. Only a `CUSTOMER`
with a `customer_id` may call it. Any other authenticated caller SHALL receive `403` with code
`FORBIDDEN`.

#### Scenario: Customer lists their vehicles
- **WHEN** customer Emma, who owns a Golf and a Corolla, calls `GET /api/v1/me/vehicles`, and customer Duc owns a Focus
- **THEN** the response is `200` with exactly the Golf and the Corolla, ordered by id

#### Scenario: Customer without vehicles
- **WHEN** a customer who owns no vehicle calls `GET /api/v1/me/vehicles`
- **THEN** the response is `200` with an empty `items` array

#### Scenario: Staff have no vehicles of their own
- **WHEN** an advisor or an admin calls `GET /api/v1/me/vehicles`
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Customer token without a customer id
- **WHEN** a token with role `customer` but no `customer_id` calls `GET /api/v1/me/vehicles`
- **THEN** the response is `403` with code `FORBIDDEN`
