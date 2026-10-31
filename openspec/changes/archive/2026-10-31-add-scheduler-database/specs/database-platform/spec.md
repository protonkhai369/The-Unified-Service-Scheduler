## ADDED Requirements

### Requirement: Isolated application schema
All application tables, including the migration history, SHALL be created in the `scheduler`
schema and none in `public`. On Supabase, the `anon` and `authenticated` roles MUST have no
privileges on the `scheduler` schema, so Supabase's Data API cannot read or write scheduler data.

#### Scenario: Nothing in the public schema
- **WHEN** the migrations have run on Supabase
- **THEN** the `public` schema contains no table created by the application

#### Scenario: Anonymous API role locked out
- **WHEN** the privileges of the `anon` and `authenticated` roles on schema `scheduler` are checked after migration
- **THEN** neither role has `USAGE` on the schema

#### Scenario: No public-exposure advisor finding
- **WHEN** Supabase's Security Advisor runs after migration
- **THEN** it reports no "RLS Disabled in Public" finding for any scheduler table

### Requirement: Portable versioned migrations
The schema SHALL be created only by versioned Flyway migrations that run unchanged on
Supabase-hosted PostgreSQL 17 and on plain PostgreSQL 17. The `btree_gist` extension MUST be
installed in an `extensions` schema, Supabase-only statements MUST be skipped where the Supabase roles
do not exist, and the application MUST only validate the schema, never generate it.

#### Scenario: Fresh plain PostgreSQL
- **WHEN** the application starts against an empty PostgreSQL 17 container
- **THEN** all migrations apply successfully and the application becomes ready

#### Scenario: Supabase
- **WHEN** the application starts against the Supabase project with migrations pending
- **THEN** all migrations apply successfully, `btree_gist` is installed in schema `extensions`, and `/actuator/health/readiness` reports `UP`

#### Scenario: Restart is a no-op
- **WHEN** the application restarts against a database where all migrations are applied
- **THEN** no migration runs again and startup succeeds

### Requirement: Demo data kept apart from schema and tests
Demo reference data (dealerships, service types, bays, technicians, qualifications, customers,
vehicles) SHALL live in a separate repeatable migration that is applied to local and Supabase runs
only when the reference tables are empty, and never to automated tests. Automated tests MUST run
against a throwaway PostgreSQL 17 container, never the configured database.

#### Scenario: Demo data on a fresh run
- **WHEN** the application starts for the first time against an empty database
- **THEN** the demo dealerships, bays, technicians, qualifications, customers and vehicles exist

#### Scenario: Demo data not duplicated
- **WHEN** the application restarts, or the demo script changes after data already exists
- **THEN** no demo rows are inserted a second time

#### Scenario: Later schema migrations still apply
- **WHEN** a new versioned migration is added after the demo data has been applied
- **THEN** it applies normally on the next start

#### Scenario: Tests start clean
- **WHEN** an automated test starts
- **THEN** it runs on a container database with the schema migrations only and no demo rows
