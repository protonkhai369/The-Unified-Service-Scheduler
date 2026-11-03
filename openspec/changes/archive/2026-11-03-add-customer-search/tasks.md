## 1. Migration (tests first)

- [x] 1.1 Extend the schema tests:
  - `unaccent` and `pg_trgm` are installed in schema `extensions`;
  - `f_unaccent` is `IMMUTABLE`, maps `Lê Minh Đức` to `Le Minh Duc`, and strips the same text when it is sent in NFD;
  - both GIN trigram indexes exist.

  Rename the probe to `db/probe/V3__probe.sql`, and make `DemoDataIntegrationTest` expect version `3`
- [x] 1.2 Write `V2__customer_search.sql` (design decision 2); make 1.1 pass and keep the schema tests green

## 2. Access rule (tests first)

- [x] 2.1 Unit-test `AccessPolicy.mayFindCustomers` for:
  - an admin;
  - an advisor with and without `dealership_id`;
  - a customer;
  - no role.
- [x] 2.2 Implement it; make 2.1 pass

## 3. Search (tests first)

- [x] 3.1 Write a repository integration test for the search query:
  - case, accents and NFD;
  - containment of a name and of an email;
  - typo (`lee minh duk`) and word order (`duc le`);
  - containment ranked before similarity, then by similarity, then by id;
  - the limit of 20;
  - `%` and `_` treated literally;
  - no match.
- [x] 3.2 Write `CustomerSearchIntegrationTest` with one test per scenario of the `customer-search` spec. It includes validation, roles and, with `OutputCaptureExtension`, that the search text is not logged
- [x] 3.3 Implement:
  - `CustomerRepository.search` (design decision 3);
  - `VehicleRepository.findByCustomerIdInOrderById`;
  - `CustomerMatchResponse`;
  - `CustomerSearchService` (`@PreAuthorize`, pattern escaping, vehicles grouped in result order);
  - `CustomerController` with the `q` constraint and `@SecurityRequirement`.

  Make 3.1 and 3.2 pass

## 4. OpenAPI and documentation

- [x] 4.1 Extend `OpenApiIntegrationTest`: `GET /api/v1/customers` carries the bearer scheme and documents `q`
- [x] 4.2 Update the documentation:
  - `docs/SYSTEM_DESIGN.md`:
    - the API table and the roles notes;
    - a customer-search section with the measured similarity table;
    - the assumptions;
    - "future work": keyword and semantic service search, with the evaluation-set idea.
  - `AGENTS.md` and `openspec/config.yaml` (current state, extensions in schema `extensions`, `f_unaccent`);
  - `docs/AI_LOG.md`, including the trigram measurements and the corrected scenario.

## 5. Verification

- [x] 5.1 Run `./mvnw clean test` and quote the Surefire summary; every test passes
- [x] 5.2 Map every scenario of `specs/customer-search` to a passing test and list any gap
- [x] 5.3 Run `openspec validate add-customer-search --strict` and `openspec validate --all --strict`
