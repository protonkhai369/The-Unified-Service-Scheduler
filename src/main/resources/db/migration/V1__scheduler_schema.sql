-- Scenario A data model. Runs unchanged on Supabase and on plain PostgreSQL 17.
-- Flyway runs it with search_path = ${flyway:defaultSchema}, so unqualified objects land there.
-- Never edit this file once applied anywhere; fixes go into V2 and later.

-- Supabase keeps extensions in "extensions"; plain PostgreSQL does not have that schema. GiST operator
-- classes are resolved by type and access method, not through search_path.
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA extensions;

-- Reference data -----------------------------------------------------------------------------------

CREATE TABLE dealership (
    id        bigint GENERATED ALWAYS AS IDENTITY,
    name      text   NOT NULL,
    time_zone text   NOT NULL, -- IANA zone id, e.g. Europe/London; validated by the application
    opens     time   NOT NULL, -- same opening hours every day, in time_zone
    closes    time   NOT NULL,
    CONSTRAINT dealership_pk PRIMARY KEY (id),
    CONSTRAINT dealership_name_length CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT dealership_time_zone_length CHECK (char_length(time_zone) BETWEEN 1 AND 64),
    CONSTRAINT dealership_hours_order CHECK (closes > opens)
);

CREATE TABLE service_type (
    id               bigint  GENERATED ALWAYS AS IDENTITY,
    code             text    NOT NULL,
    name             text    NOT NULL,
    duration_minutes integer NOT NULL,
    CONSTRAINT service_type_pk PRIMARY KEY (id),
    CONSTRAINT service_type_code_unique UNIQUE (code),
    CONSTRAINT service_type_code_length CHECK (char_length(code) BETWEEN 1 AND 50),
    CONSTRAINT service_type_name_length CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT service_type_duration_positive CHECK (duration_minutes > 0)
);

CREATE TABLE service_bay (
    id            bigint  GENERATED ALWAYS AS IDENTITY,
    dealership_id bigint  NOT NULL,
    name          text    NOT NULL,
    active        boolean NOT NULL DEFAULT true,
    CONSTRAINT service_bay_pk PRIMARY KEY (id),
    CONSTRAINT service_bay_dealership_fk FOREIGN KEY (dealership_id) REFERENCES dealership (id),
    CONSTRAINT service_bay_name_unique UNIQUE (dealership_id, name),
    -- Target of appointment_bay_fk; also serves as the index on dealership_id.
    CONSTRAINT service_bay_dealership_id_unique UNIQUE (dealership_id, id),
    CONSTRAINT service_bay_name_length CHECK (char_length(name) BETWEEN 1 AND 200)
);

CREATE TABLE technician (
    id            bigint  GENERATED ALWAYS AS IDENTITY,
    dealership_id bigint  NOT NULL,
    name          text    NOT NULL,
    active        boolean NOT NULL DEFAULT true,
    CONSTRAINT technician_pk PRIMARY KEY (id),
    CONSTRAINT technician_dealership_fk FOREIGN KEY (dealership_id) REFERENCES dealership (id),
    -- Target of appointment_technician_fk; also serves as the index on dealership_id.
    CONSTRAINT technician_dealership_id_unique UNIQUE (dealership_id, id),
    CONSTRAINT technician_name_length CHECK (char_length(name) BETWEEN 1 AND 200)
);

-- A qualification. Referenced by appointments, so it is retired with active = false, not deleted.
CREATE TABLE technician_skill (
    technician_id   bigint  NOT NULL,
    service_type_id bigint  NOT NULL,
    active          boolean NOT NULL DEFAULT true,
    CONSTRAINT technician_skill_pk PRIMARY KEY (technician_id, service_type_id),
    CONSTRAINT technician_skill_technician_fk FOREIGN KEY (technician_id) REFERENCES technician (id),
    CONSTRAINT technician_skill_service_type_fk FOREIGN KEY (service_type_id) REFERENCES service_type (id)
);
-- "Which technicians are qualified for this service type?" (allocation).
CREATE INDEX technician_skill_service_type_idx ON technician_skill (service_type_id);

CREATE TABLE customer (
    id    bigint GENERATED ALWAYS AS IDENTITY,
    name  text   NOT NULL,
    email text   NOT NULL,
    phone text,
    CONSTRAINT customer_pk PRIMARY KEY (id),
    CONSTRAINT customer_email_unique UNIQUE (email),
    CONSTRAINT customer_name_length CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT customer_email_length CHECK (char_length(email) BETWEEN 3 AND 320),
    CONSTRAINT customer_phone_length CHECK (char_length(phone) BETWEEN 1 AND 50)
);

CREATE TABLE vehicle (
    id          bigint  GENERATED ALWAYS AS IDENTITY,
    customer_id bigint  NOT NULL,
    vin         text    NOT NULL,
    make        text    NOT NULL,
    model       text    NOT NULL,
    model_year  integer,
    CONSTRAINT vehicle_pk PRIMARY KEY (id),
    CONSTRAINT vehicle_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT vehicle_vin_unique UNIQUE (vin),
    -- ISO 3779: 17 characters, digits and capital letters except I, O and Q.
    CONSTRAINT vehicle_vin_format CHECK (vin ~ '^[A-HJ-NPR-Z0-9]{17}$'),
    CONSTRAINT vehicle_make_length CHECK (char_length(make) BETWEEN 1 AND 200),
    CONSTRAINT vehicle_model_length CHECK (char_length(model) BETWEEN 1 AND 200)
);
CREATE INDEX vehicle_customer_idx ON vehicle (customer_id);

-- Appointments -------------------------------------------------------------------------------------

CREATE TABLE appointment (
    -- Handed to customers, so not guessable. The application may supply its own.
    id                  uuid        NOT NULL DEFAULT gen_random_uuid(),
    dealership_id       bigint      NOT NULL,
    customer_id         bigint      NOT NULL,
    vehicle_id          bigint      NOT NULL,
    service_type_id     bigint      NOT NULL,
    service_bay_id      bigint      NOT NULL,
    technician_id       bigint      NOT NULL,
    -- end_at is stored, not derived from the service type, so a later duration change keeps history.
    start_at            timestamptz NOT NULL,
    end_at              timestamptz NOT NULL,
    -- Half-open [start_at, end_at): an appointment may start exactly when another ends. The CASE lets
    -- an inverted range reach appointment_time_order (23514) instead of failing inside tstzrange()
    -- with a generic 22000; every stored row passes that check, so period is never NULL.
    period              tstzrange   GENERATED ALWAYS AS (
                            CASE WHEN start_at < end_at THEN tstzrange(start_at, end_at, '[)') END) STORED,
    status              text        NOT NULL,
    idempotency_key     text        NOT NULL,
    request_fingerprint text        NOT NULL, -- SHA-256 of the booking request, lower-case hex
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0, -- optimistic locking (JPA @Version)
    CONSTRAINT appointment_pk PRIMARY KEY (id),
    CONSTRAINT appointment_dealership_fk FOREIGN KEY (dealership_id) REFERENCES dealership (id),
    CONSTRAINT appointment_customer_fk FOREIGN KEY (customer_id) REFERENCES customer (id),
    -- Ownership (customer_id, vehicle_id) is checked when booking, not here: vehicles change owners.
    CONSTRAINT appointment_vehicle_fk FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    CONSTRAINT appointment_service_type_fk FOREIGN KEY (service_type_id) REFERENCES service_type (id),
    -- The bay and the technician belong to the appointment's dealership ...
    CONSTRAINT appointment_bay_fk FOREIGN KEY (dealership_id, service_bay_id)
        REFERENCES service_bay (dealership_id, id),
    CONSTRAINT appointment_technician_fk FOREIGN KEY (dealership_id, technician_id)
        REFERENCES technician (dealership_id, id),
    -- ... and the technician is qualified for the service type.
    CONSTRAINT appointment_qualification_fk FOREIGN KEY (technician_id, service_type_id)
        REFERENCES technician_skill (technician_id, service_type_id),
    CONSTRAINT appointment_time_order CHECK (end_at > start_at),
    CONSTRAINT appointment_status_valid CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT appointment_idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,100}$'),
    CONSTRAINT appointment_request_fingerprint_format CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    -- Written by the same INSERT as the appointment, so key and booking commit atomically.
    CONSTRAINT appointment_idempotency_key_unique UNIQUE (idempotency_key),
    -- No double booking. Checked against concurrent, uncommitted inserts too; cancelling frees the
    -- resources. The names are a contract: the booking flow retries on bay/technician conflicts and
    -- reports VEHICLE_ALREADY_BOOKED on the vehicle one.
    CONSTRAINT appointment_no_bay_overlap
        EXCLUDE USING gist (service_bay_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED'),
    CONSTRAINT appointment_no_technician_overlap
        EXCLUDE USING gist (technician_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED'),
    CONSTRAINT appointment_no_vehicle_overlap
        EXCLUDE USING gist (vehicle_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED')
);
-- Foreign keys are not indexed automatically. Bay, technician and vehicle lookups use the GiST indexes.
CREATE INDEX appointment_customer_idx ON appointment (customer_id);
CREATE INDEX appointment_service_type_idx ON appointment (service_type_id);
-- "A dealership's bookings for a day".
CREATE INDEX appointment_dealership_start_idx ON appointment (dealership_id, start_at) WHERE status = 'CONFIRMED';

-- Supabase Data API --------------------------------------------------------------------------------

-- Supabase exposes schemas to anonymous callers through its Data API (PostgREST). This backend only
-- uses JDBC, so the API roles get nothing here, now or for tables added later. Plain PostgreSQL has
-- no such roles, and the block does nothing.
DO $$
DECLARE
    api_role text;
BEGIN
    FOR api_role IN SELECT rolname FROM pg_catalog.pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', '${flyway:defaultSchema}', api_role);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', '${flyway:defaultSchema}', api_role);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', '${flyway:defaultSchema}', api_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE ALL ON TABLES FROM %I',
                       '${flyway:defaultSchema}', api_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM %I',
                       '${flyway:defaultSchema}', api_role);
    END LOOP;
END
$$;
