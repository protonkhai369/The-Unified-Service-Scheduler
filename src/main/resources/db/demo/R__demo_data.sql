-- Demo reference data for local and Supabase runs. Tests never load it (they use db/migration only).
-- A repeatable migration: Flyway runs it after the versioned ones and again whenever this file
-- changes, so it inserts only into an empty database and never duplicates rows.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM dealership) THEN
        RAISE NOTICE 'Demo data skipped: dealership already has rows';
        RETURN;
    END IF;

    INSERT INTO dealership (name, time_zone, opens, closes) VALUES
        ('Riverside Motors London', 'Europe/London',    '08:00', '18:00'),
        ('Saigon Auto Centre',      'Asia/Ho_Chi_Minh', '07:30', '17:30');

    INSERT INTO service_type (code, name, duration_minutes) VALUES
        ('OIL_CHANGE',    'Oil and filter change',                45),
        ('TYRE_ROTATION', 'Tyre rotation and balancing',          30),
        ('BRAKE_SERVICE', 'Brake inspection and pad replacement', 90),
        ('FULL_SERVICE',  'Full annual service',                  180)
    ON CONFLICT (code) DO NOTHING;

    INSERT INTO service_bay (dealership_id, name)
    SELECT d.id, b.name
    FROM (VALUES
        ('Riverside Motors London', 'Bay 1'),
        ('Riverside Motors London', 'Bay 2'),
        ('Riverside Motors London', 'Bay 3'),
        ('Saigon Auto Centre',      'Bay A'),
        ('Saigon Auto Centre',      'Bay B')
    ) AS b (dealership, name)
    JOIN dealership d ON d.name = b.dealership;

    INSERT INTO technician (dealership_id, name, active)
    SELECT d.id, t.name, t.active
    FROM (VALUES
        ('Riverside Motors London', 'Alice Morgan',  true),
        ('Riverside Motors London', 'Ben Carter',    true),
        ('Riverside Motors London', 'Chloe Evans',   false), -- inactive: never allocated
        ('Saigon Auto Centre',      'Nguyen Van An', true),
        ('Saigon Auto Centre',      'Tran Thi Binh', true)
    ) AS t (dealership, name, active)
    JOIN dealership d ON d.name = t.dealership;

    INSERT INTO technician_skill (technician_id, service_type_id)
    SELECT t.id, s.id
    FROM (VALUES
        ('Alice Morgan',  'OIL_CHANGE'), ('Alice Morgan',  'BRAKE_SERVICE'), ('Alice Morgan', 'FULL_SERVICE'),
        ('Ben Carter',    'OIL_CHANGE'), ('Ben Carter',    'TYRE_ROTATION'),
        ('Chloe Evans',   'BRAKE_SERVICE'), ('Chloe Evans', 'FULL_SERVICE'),
        ('Nguyen Van An', 'OIL_CHANGE'), ('Nguyen Van An', 'TYRE_ROTATION'),
        ('Nguyen Van An', 'BRAKE_SERVICE'), ('Nguyen Van An', 'FULL_SERVICE'),
        ('Tran Thi Binh', 'OIL_CHANGE'), ('Tran Thi Binh', 'TYRE_ROTATION')
    ) AS q (technician, service_type)
    JOIN technician t ON t.name = q.technician
    JOIN service_type s ON s.code = q.service_type;

    INSERT INTO customer (name, email, phone) VALUES
        ('Emma Wilson', 'emma.wilson@example.com', '+44 20 7946 0123'),
        ('Le Minh Duc', 'minh.duc@example.com',    NULL)
    ON CONFLICT (email) DO NOTHING;

    -- Emma owns two vehicles, so two bookings at the same time can be demonstrated.
    INSERT INTO vehicle (customer_id, vin, make, model, model_year)
    SELECT c.id, v.vin, v.make, v.model, v.model_year
    FROM (VALUES
        ('emma.wilson@example.com', 'WVWZZZ1KZ8W000001', 'Volkswagen', 'Golf',    2019),
        ('emma.wilson@example.com', 'JTDBR32E720000002', 'Toyota',     'Corolla', 2021),
        ('minh.duc@example.com',    'WF0AXXGCDA0000003', 'Ford',       'Focus',   2018)
    ) AS v (email, vin, make, model, model_year)
    JOIN customer c ON c.email = v.email
    ON CONFLICT (vin) DO NOTHING;
END
$$;
