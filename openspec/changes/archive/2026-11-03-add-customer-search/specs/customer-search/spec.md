## ADDED Requirements

### Requirement: Staff search customers by name or email
`GET /api/v1/customers?q=` SHALL return the customers whose name or email matches `q`, as
`{"items": [...]}` of at most 20 customers. Each item SHALL have `id`, `name`, `email`, `phone`, and
`vehicles`: the customer's vehicles, each with `id`, `vin`, `make`, `model` and `modelYear`, ordered
by id.

Matching SHALL ignore letter case, accents and the Unicode normalisation form of `q`, and compares
`q` with a name or email that is normalised the same way. A customer SHALL match when:
- `q` is contained in their normalised name or email; or
- `q` is similar enough to their normalised name, by trigram similarity at least 0.3, so that typing
  mistakes and a different word order still match.

Containment matches SHALL come before similarity-only matches. Within each group, more similar names
come first, then lower ids.

#### Scenario: Case and accents are ignored
- **WHEN** customers "Lê Minh Đức" and "Emma Wilson" exist, and an advisor searches `le minh duc`, and then `LÊ MINH ĐỨC`
- **THEN** both searches return "Lê Minh Đức" and not "Emma Wilson"

#### Scenario: Decomposed input
- **WHEN** an advisor searches "Đức" sent in Unicode NFD, with the accent marks as separate combining characters
- **THEN** "Lê Minh Đức" is returned, as for the composed form

#### Scenario: Part of a name
- **WHEN** an advisor searches `duc`, and then `wil`
- **THEN** the first search returns "Lê Minh Đức" and the second "Emma Wilson"

#### Scenario: Typing mistake
- **WHEN** an advisor searches `lee minh duk`
- **THEN** "Lê Minh Đức" is returned

#### Scenario: Different word order
- **WHEN** an advisor searches `duc le`
- **THEN** "Lê Minh Đức" is returned

#### Scenario: Email
- **WHEN** customer "Lê Minh Đức" has email `minh.duc@example.com`, and an advisor searches `minh.duc@`
- **THEN** "Lê Minh Đức" is returned

#### Scenario: Vehicles come with the customer
- **WHEN** the matching customer owns a Focus and a Golf
- **THEN** their item lists both vehicles, ordered by id

#### Scenario: Containment before similarity
- **WHEN** "Lee Minh Duk" was created before "Lê Minh Đức", and an advisor searches `le minh duc`
- **THEN** "Lê Minh Đức", which contains the query, is listed first, and "Lee Minh Duk", which only resembles it, comes second

#### Scenario: At most twenty results
- **WHEN** 25 customers are named "Nguyen …", and an advisor searches `nguyen`
- **THEN** exactly 20 items are returned

#### Scenario: No match
- **WHEN** an advisor searches `zzzz`
- **THEN** the response is `200` with an empty `items` array

### Requirement: Search query validation
`q` SHALL be required and SHALL have 2 to 100 characters after leading and trailing whitespace are
removed. Otherwise the response SHALL be `400` with code `VALIDATION_FAILED` and an `errors` entry
for `q`.

#### Scenario: Missing query
- **WHEN** an advisor calls `GET /api/v1/customers` without `q`
- **THEN** the response is `400` with code `VALIDATION_FAILED` and an `errors` entry for `q`

#### Scenario: Query too short
- **WHEN** an advisor searches `d`, or `"  d  "`
- **THEN** the response is `400` with code `VALIDATION_FAILED`

### Requirement: Customer search is for staff only
Only an `ADVISOR` with a `dealership_id` or an `ADMIN` SHALL search customers. Any other
authenticated caller SHALL receive `403` with code `FORBIDDEN`, and a request without a valid token
SHALL receive `401` with code `UNAUTHENTICATED`. The search text SHALL NOT be written to the logs.

#### Scenario: Advisor and admin may search
- **WHEN** an advisor of any dealership, or an admin, searches `duc`
- **THEN** the response is `200`

#### Scenario: Customer may not search
- **WHEN** a customer searches `duc`
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Advisor without a dealership
- **WHEN** a token with role `advisor` but no `dealership_id` searches `duc`
- **THEN** the response is `403` with code `FORBIDDEN`

#### Scenario: Search without a token
- **WHEN** `GET /api/v1/customers?q=duc` is sent without a token
- **THEN** the response is `401` with code `UNAUTHENTICATED`

#### Scenario: Search text is not logged
- **WHEN** an advisor searches `Lê Minh Đức`
- **THEN** no log line of that request contains the search text
