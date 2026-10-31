-- Test-only stand-in for the next real schema migration (DemoDataIntegrationTest). Never on the
-- application's classpath.
ALTER TABLE service_type ADD COLUMN probe_note text;
