INSERT INTO service_levels (code, multiplier_bps, sla_minutes) VALUES
    ('STANDARD', 10000, 240),
    ('EXPRESS', 15000, 120);

INSERT INTO pricing_zones (code, name, geom, priority) VALUES
    ('IST_METRO', 'Istanbul metropolitan area',
     ST_GeogFromText('SRID=4326;MULTIPOLYGON(((28.60 40.80, 29.50 40.80, 29.50 41.30, 28.60 41.30, 28.60 40.80)))'), 0),
    ('IST_EUROPE_CORE', 'Istanbul European side core',
     ST_GeogFromText('SRID=4326;MULTIPOLYGON(((28.90 40.98, 29.00 40.98, 29.00 41.10, 28.90 41.10, 28.90 40.98)))'), 10),
    ('IST_ASIA_CORE', 'Istanbul Asian side core',
     ST_GeogFromText('SRID=4326;MULTIPOLYGON(((29.00 40.88, 29.15 40.88, 29.15 41.05, 29.00 41.05, 29.00 40.88)))'), 10);

INSERT INTO zone_rates (zone_id, base_fee_minor, per_km_minor, included_weight_grams, per_extra_kg_minor, min_fee_minor)
SELECT id, 6500, 800, 5000, 700, 8000 FROM pricing_zones WHERE code = 'IST_METRO';

INSERT INTO zone_rates (zone_id, base_fee_minor, per_km_minor, included_weight_grams, per_extra_kg_minor, min_fee_minor)
SELECT id, 4500, 600, 5000, 500, 5500 FROM pricing_zones WHERE code IN ('IST_EUROPE_CORE', 'IST_ASIA_CORE');
