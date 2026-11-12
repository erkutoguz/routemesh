CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    topic        TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

-- Gönderi okuma modeli. ShipmentStatusChanged, ShipmentCreated'dan önce gelirse merchant_id, amount_minor ve
-- currency boş taslak satır açılır; ShipmentCreated bu alanları sonradan tamamlar (durum ve sürümü ezmez).
CREATE TABLE shipment_view (
    id           UUID PRIMARY KEY,
    merchant_id  UUID,
    status       TEXT        NOT NULL,
    courier_id   UUID,
    version      BIGINT      NOT NULL CHECK (version >= 0),
    amount_minor BIGINT CHECK (amount_minor > 0),
    currency     TEXT CHECK (char_length(currency) = 3),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT shipment_view_skeleton_check CHECK (
        (merchant_id IS NULL) = (amount_minor IS NULL) AND (merchant_id IS NULL) = (currency IS NULL))
);

CREATE INDEX shipment_view_merchant_idx ON shipment_view (merchant_id, created_at DESC, id DESC);
CREATE INDEX shipment_view_status_idx ON shipment_view (status, created_at DESC, id DESC);
CREATE INDEX shipment_view_courier_idx ON shipment_view (courier_id, created_at DESC, id DESC);

GRANT USAGE ON SCHEMA public TO search_app;
GRANT SELECT, INSERT, DELETE ON processed_events TO search_app;
GRANT SELECT, INSERT, UPDATE ON shipment_view TO search_app;
