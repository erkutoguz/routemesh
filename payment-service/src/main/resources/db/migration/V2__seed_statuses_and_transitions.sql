INSERT INTO payment_statuses (code, terminal) VALUES
    ('CREATED', false),
    ('AUTHORIZED', false),
    ('CAPTURED', false),
    ('PARTIALLY_REFUNDED', false),
    ('REFUNDED', true),
    ('VOIDED', true),
    ('EXPIRED', false),
    ('FAILED', true);

INSERT INTO payment_transitions (from_status, to_status) VALUES
    ('CREATED', 'AUTHORIZED'),
    ('CREATED', 'EXPIRED'),
    ('CREATED', 'FAILED'),
    ('AUTHORIZED', 'CAPTURED'),
    ('AUTHORIZED', 'VOIDED'),
    ('CAPTURED', 'PARTIALLY_REFUNDED'),
    ('CAPTURED', 'REFUNDED'),
    ('PARTIALLY_REFUNDED', 'REFUNDED'),
    ('EXPIRED', 'VOIDED'),
    ('EXPIRED', 'REFUNDED');
