## Context

- `customer` has `name`, `email` (unique) and `phone`. There is no index useful for search, and no
  search endpoint. `vehicle` holds the VIN, make and model. There is no plate column.
- V1 creates schema `extensions` and installs `btree_gist` there. Every table lives in `scheduler`.
- The application's connection pool sets `schema: scheduler`, so its `search_path` is `scheduler`
  only. Flyway also runs with `search_path = scheduler`.
- Access rules live in the pure `AccessPolicy`. `@PreAuthorize` guards operations decided by the
  caller and the arguments (`add-self-service-queries`), and `AccessDeniedException` maps to
  `403 FORBIDDEN`.
- The access log writes `request.getRequestURI()`, which excludes the query string.
- These were measured on `postgres:17-alpine`, the test image, during exploration:
  - both `unaccent` and `pg_trgm` are available;
  - `unaccent` maps `Đ→D`, `đ→d`, `ế→e` and `ữ→u`, and also strips NFD input;
  - trigram `similarity` scores:

    | query | name | similarity |
    | --- | --- | --- |
    | `lee minh duk` | `le minh duc` | 0.56 |
    | `duc le` | `le minh duc` | 0.58 |
    | `emma wilsn` | `emma wilson` | 0.64 |
    | `duc` | `le minh duc` | 0.33 |
    | `wil` | `emma wilson` | 0.23 |
    | `minh` | `mihn tuan` | 0.15 |
    | `zzzz` | `emma wilson` | 0.00 |

## Goals / Non-Goals

**Goals:**
- Accent-, case- and typo-tolerant customer lookup for staff, backed by an index.
- One request for the whole desk step: the customer and their vehicles.
- No personal data in logs, and no customer-to-customer lookup.

**Non-Goals:** phone, VIN or plate search; per-dealership visibility; ranking tuning; pagination;
service search on the server (see the proposal).

## Decisions

### 1. PostgreSQL `unaccent` and `pg_trgm`, not a collation or full-text search

| Option | Accents | Substring | Typos | Verdict |
| --- | --- | --- | --- | --- |
| `unaccent` + `pg_trgm` (GIN) | yes | yes, `LIKE '%q%'` through the index | yes, `%` similarity | chosen |
| ICU collation `und-u-ks-level1` | yes | no: PostgreSQL 17 rejects `LIKE` on nondeterministic collations | no | rejected |
| Full-text search (`tsvector`) | yes, with `unaccent` | whole words only (`duc` does not find `đức anh`) | no | rejected |
| Normalised copy in Java or a column | yes | needs its own index anyway | no | rejected: customers are master data the application does not write |

The `%` operator uses `pg_trgm.similarity_threshold`, whose default is `0.3`. The measured
typo-tolerance and word-order examples score 0.56 to 0.64, so the default needs no tuning. Short
queries are covered by containment instead (`wil` scores 0.23 but is contained).

### 2. Migration `V2__customer_search.sql`

```sql
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;

CREATE FUNCTION f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN extensions.unaccent('extensions.unaccent'::regdictionary, $1);

CREATE INDEX customer_name_search_idx ON customer USING gin (f_unaccent(lower(name)) extensions.gin_trgm_ops);
CREATE INDEX customer_email_search_idx ON customer USING gin (f_unaccent(lower(email)) extensions.gin_trgm_ops);
```

- `unaccent()` is only `STABLE`, because its dictionary is looked up through `search_path`, and an
  index expression must be `IMMUTABLE`. The documented workaround is the two-argument form with a
  schema-qualified dictionary, wrapped in an `IMMUTABLE` function. The function lands in
  `scheduler`, which Supabase's API roles cannot use (V1), so it is not exposed through the Data
  API.
- If the unaccent rules ever change, both indexes need a `REINDEX`. The rules ship with PostgreSQL
  and change only on a major upgrade.
- The test-only `db/probe/V2__probe.sql` becomes `V3__probe.sql`. It stands for "the next schema
  migration" in `DemoDataIntegrationTest`, which now expects version `3`.

### 3. The query

A native query on `CustomerRepository`. The operators from `extensions` are schema-qualified,
because the application's `search_path` is `scheduler`:

```sql
WITH input AS (SELECT f_unaccent(lower(:term)) AS q, f_unaccent(lower(:pattern)) AS p)
SELECT c.id, c.name, c.email, c.phone
FROM customer c, input
WHERE f_unaccent(lower(c.name)) LIKE '%' || input.p || '%' ESCAPE '\'
   OR f_unaccent(lower(c.email)) LIKE '%' || input.p || '%' ESCAPE '\'
   OR f_unaccent(lower(c.name)) OPERATOR(extensions.%) input.q
ORDER BY (f_unaccent(lower(c.name)) LIKE '%' || input.p || '%' ESCAPE '\'
          OR f_unaccent(lower(c.email)) LIKE '%' || input.p || '%' ESCAPE '\') DESC,
         extensions.similarity(f_unaccent(lower(c.name)), input.q) DESC,
         c.id
LIMIT 20
```

- `:term` is the trimmed input. `:pattern` is the same input with `\`, `%` and `_` escaped in Java,
  so `50%` searches literally. The escaping lives in a `default` method, `CustomerRepository.search(term)`,
  next to the query, so the repository test exercises it too. Normalisation happens in SQL with the same function as the index, so
  the query and the stored data can never disagree.
- Both `LIKE` and `%` are served by the GIN trigram indexes, combined by a bitmap OR.
- A spike on `postgres:17-alpine` ran the migration and this query with `search_path = scheduler`
  over 2,004 customers. `EXPLAIN` showed a `BitmapOr` of three `Bitmap Index Scan`s, two on
  `customer_name_search_idx` and one on `customer_email_search_idx`, and no sequential scan. Results:

  | query | returned, in order |
  | --- | --- |
  | `le minh duc` | Lê Minh Đức, Lee Minh Duk |
  | `lee minh duk` | Lee Minh Duk, Lê Minh Đức |
  | `duc` | Lê Minh Đức |
  | `duc le` | Lê Minh Đức |
  | `wil` | Emma Wilson |
  | `50%` | "Percent 50% Club" first: the escaped `%` is a literal |
  | NFD "Đức" | Lê Minh Đức |
  | `zzzz` | nothing |

  A query that looks like an email (`minh.duc@`) also returns names that merely resemble it ("Lee
  Minh Duk"), because similarity applies to the name. Containment matches still come first.
- The vehicles of the result come from one more query,
  `VehicleRepository.findByCustomerIdInOrderById`, grouped in Java. That makes two statements per
  search, with no N+1.

### 4. Validation

`@RequestParam @Pattern(regexp = "\\s*\\S.{0,98}\\S\\s*") String q` requires 2 to 100 characters
after trimming. A missing `q` is Spring MVC's `MissingServletRequestParameterException`. Both
already answer `400 VALIDATION_FAILED` naming `q`. The constraint message names the rule, never the
value.

### 5. Access and privacy

- `AccessPolicy.mayFindCustomers(user)`: `ADMIN`, or an `ADVISOR` with a `dealership_id`. It is
  enforced by `@PreAuthorize` on the public `CustomerSearchService.search` (the convention in
  `AGENTS.md`).
- The search text appears neither in the access log (path only) nor in any application log line. A
  test captures the output and checks for it.
- An item has `id`, `name`, `email`, `phone` and `vehicles`. It has nothing else, for example no
  appointment history.

### 6. Where the code goes

```
customer/controller/CustomerController       GET /api/v1/customers?q=   (@SecurityRequirement bearer)
customer/service/CustomerSearchService       @PreAuthorize; trims; groups vehicles in result order
customer/dto/response/CustomerMatchResponse  id, name, email, phone, vehicles (account's VehicleResponse)
customer/repository/CustomerRepository       + searchMatching(term, pattern) (native) and default search(term), which escapes
customer/repository/VehicleRepository        + findByCustomerIdInOrderById
security/AccessPolicy                        + mayFindCustomers
db/migration/V2__customer_search.sql         extensions, f_unaccent, two GIN indexes
```

## Concurrency

The search is a read-only transaction at `READ COMMITTED`, without locks. It is independent of
bookings. Customers and vehicles are master data, so no write path in this application competes with
it.

## Metrics and log events

No new metric or log event. `http_server_requests_seconds{uri="/api/v1/customers"}` counts and
times searches; its URI tag is the template, without the query. The access log records the path.

## Risks / Trade-offs

- [`%` depends on the session setting `pg_trgm.similarity_threshold`] → Nothing changes it, and it
  defaults to 0.3. The typo and word-order scenarios lock the behaviour, so a changed default would
  fail the tests.
- [An existing Supabase project might have `pg_trgm` or `unaccent` installed in another schema] →
  `CREATE EXTENSION IF NOT EXISTS ... WITH SCHEMA extensions` would then keep the old location, and
  the schema-qualified references would fail. Supabase installs extensions in `extensions` by
  default. The migration fails loudly on start, rather than silently.
- [GIN indexes slow down customer writes] → Customers are master data, written rarely and not by
  this application.
- [Advisors can find any customer] → This is a stated assumption. Limiting them to their dealership
  is a recorded non-goal.

## Migration Plan

- V2 runs on the next application start against Supabase. It creates two standard extensions and
  two indexes. The demo database is small, so this is quick.
- Rollback: drop the two indexes and the function. The extensions are harmless to keep. A forward
  `V3` would do it, because applied migrations are never edited.

## Open Questions

None.
