ALTER TABLE shipments ADD COLUMN weight_grams integer;
ALTER TABLE shipments ADD CONSTRAINT shipments_weight_check CHECK (weight_grams IS NULL OR weight_grams > 0);

INSERT INTO shipment_transitions (from_status, to_status, actor, requires_pod) VALUES
    ('READY_FOR_DISPATCH', 'ON_HOLD', 'SYSTEM', false);
