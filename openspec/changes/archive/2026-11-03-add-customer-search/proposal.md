## Why

Advisors may book for any customer at their dealership (`add-supabase-auth`), but the API gives
them no way to find that customer's id or vehicles. The desk flow it blocks:
- a customer calls and says "I'm Đức, the Focus";
- the advisor looks them up;
- the advisor books on the customer's behalf.

People type names as they say them: without accents or capitals, only part of the name, with
typos, or in another word order ("duc le" for "Lê Minh Đức"). An exact match finds none of these.

This serves Scenario A requirement 1 from the staff side, since a booking needs a specific customer
and vehicle. It also serves performance, because the search is index-backed instead of scanning
every customer. Scope is deliberately trimmed. The explore session ranked phone, VIN and plate
search, per-dealership customer visibility, and service search on the server as later work.

## What Changes

- `GET /api/v1/customers?q=` for advisors (with a `dealership_id`) and admins. Any other caller gets
  `403 FORBIDDEN`; without a token, `401`.
- Matching on the customer's name and email:
  - **case- and accent-insensitive**: `le minh duc`, `LÊ MINH ĐỨC` and decomposed (NFD) input all
    find "Lê Minh Đức";
  - **substring**: `duc` and `minh.duc@` match;
  - **typo- and order-tolerant**: `lee minh duk` and `duc le` match, by trigram similarity at
    PostgreSQL's default threshold (0.3). There is no tuning.
- Result: `{"items": [...]}` of at most 20 customers. Each has `id`, `name`, `email`, `phone` and its
  `vehicles` (`id`, `vin`, `make`, `model`, `modelYear`, ordered by id), so the advisor picks a
  customer and a vehicle in one request. The order is:
  1. substring matches before typo matches;
  2. then by similarity;
  3. then by id.
- `q` is required and must be 2 to 100 characters after trimming; otherwise the answer is
  `400 VALIDATION_FAILED`.
- The search text never appears in logs. The access log records the path without the query string.
- Migration `V2__customer_search.sql`:
  - extensions `unaccent` and `pg_trgm` in schema `extensions`;
  - an `IMMUTABLE` wrapper `f_unaccent(text)` in schema `scheduler`;
  - GIN trigram indexes on the normalised name and on the lowercased email.

## Capabilities

### New Capabilities

- `customer-search`: staff search for customers by name or email, accent- and typo-tolerant, with
  their vehicles.

### Modified Capabilities

None.

## Non-goals

- Searching by phone number, VIN or licence plate (the schema has no plate column).
- Limiting an advisor to customers who already visited their dealership.
- Tuning the similarity threshold or ranking weights, and pagination (a search returns the top 20).
- Searching service types on the server. The web client filters the small public list itself.
  Keyword and semantic (embedding) search are recorded in `docs/SYSTEM_DESIGN.md` as future work.
- A combined search box over customers, vehicles and services.

## Assumptions

Also recorded in `docs/SYSTEM_DESIGN.md`:

- An advisor may find any customer, because they may book for any customer at their dealership, and
  a walk-in may be new to it. Customers never search other customers.
- A search returns only what the desk needs to confirm identity and pick a vehicle: name, email,
  phone and vehicles.
- Results are ordered by relevance, an exception to "ordered by id".
- The data is stored as typed, with accents. Normalisation happens in the query and in the index
  expression, never in the stored data.

## Impact

- New migration `src/main/resources/db/migration/V2__customer_search.sql`.
  - The test-only probe `db/probe/V2__probe.sql` becomes `V3__probe.sql`, still "the next migration".
  - `DemoDataIntegrationTest` is adjusted to match.
- Code:
  - `customer.controller`, `customer.service` and `customer.dto.response` for the endpoint;
  - a native search query on `CustomerRepository`;
  - `VehicleRepository.findByCustomerIdInOrderById`;
  - `AccessPolicy.mayFindCustomers`.
- Live Supabase: the migration creates two standard extensions, which Supabase supports, and runs
  once on the next start.
- Tests and docs: `SYSTEM_DESIGN.md`, `AGENTS.md`, `openspec/config.yaml`, `AI_LOG.md`.
