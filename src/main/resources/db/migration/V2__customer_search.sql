CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;

-- unaccent() is only STABLE; the schema-qualified dictionary makes this wrapper safe to declare IMMUTABLE.
CREATE FUNCTION f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN extensions.unaccent('extensions.unaccent'::regdictionary, $1);

CREATE INDEX customer_name_search_idx ON customer USING gin (f_unaccent(lower(name)) extensions.gin_trgm_ops);
CREATE INDEX customer_email_search_idx ON customer USING gin (f_unaccent(lower(email)) extensions.gin_trgm_ops);
