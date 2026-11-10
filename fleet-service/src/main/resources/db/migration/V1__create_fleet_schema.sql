CREATE TABLE couriers (
    courier_id         uuid        PRIMARY KEY,
    status             text        NOT NULL DEFAULT 'OFFLINE',
    max_weight_grams   integer     NOT NULL,
    active_shipment_id uuid,
    fence_token        bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT couriers_status_check CHECK (status IN ('OFFLINE', 'IDLE', 'BUSY')),
    CONSTRAINT couriers_capacity_check CHECK (max_weight_grams > 0),
    CONSTRAINT couriers_busy_check CHECK ((status = 'BUSY') = (active_shipment_id IS NOT NULL))
);

-- Bir gönderi aynı anda en fazla bir kuryenin aktif gönderisi olabilir
CREATE UNIQUE INDEX couriers_active_shipment_idx ON couriers (active_shipment_id)
    WHERE active_shipment_id IS NOT NULL;

CREATE TABLE assignment_offers (
    offer_id     uuid        PRIMARY KEY,
    shipment_id  uuid        NOT NULL,
    courier_id   uuid        NOT NULL REFERENCES couriers (courier_id),
    status       text        NOT NULL,
    weight_grams integer     NOT NULL,
    fence_token  bigint      NOT NULL,
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    decided_at   timestamptz,
    CONSTRAINT assignment_offers_status_check
        CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'RELEASED')),
    CONSTRAINT assignment_offers_weight_check CHECK (weight_grams > 0),
    CONSTRAINT assignment_offers_decided_check CHECK ((status = 'OFFERED') = (decided_at IS NULL))
);

-- Bir kuryenin aynı anda en fazla bir açık teklifi olur
CREATE UNIQUE INDEX assignment_offers_open_courier_idx ON assignment_offers (courier_id)
    WHERE status = 'OFFERED';
-- Bir gönderinin aynı anda en fazla bir canlı (açık ya da kabul edilmiş) teklifi olur
CREATE UNIQUE INDEX assignment_offers_live_shipment_idx ON assignment_offers (shipment_id)
    WHERE status IN ('OFFERED', 'ACCEPTED');
CREATE INDEX assignment_offers_courier_idx ON assignment_offers (courier_id, created_at DESC);

CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    type           text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- Son savunma hattı: uygulama hatalı olsa bile kurye satırı geriye gidemez
CREATE FUNCTION enforce_courier_rules() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.fence_token < OLD.fence_token THEN
        RAISE EXCEPTION 'fence_token of courier % cannot decrease', OLD.courier_id USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'BUSY' AND NEW.status = 'OFFLINE' THEN
        RAISE EXCEPTION 'courier % cannot go offline while busy', OLD.courier_id USING ERRCODE = '23514';
    END IF;
    IF OLD.active_shipment_id IS NOT NULL AND NEW.active_shipment_id IS NOT NULL
            AND OLD.active_shipment_id <> NEW.active_shipment_id THEN
        RAISE EXCEPTION 'courier % must be released before taking another shipment', OLD.courier_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER couriers_enforce_rules BEFORE UPDATE ON couriers
    FOR EACH ROW EXECUTE FUNCTION enforce_courier_rules();

GRANT USAGE ON SCHEMA public TO fleet_app;
GRANT SELECT, INSERT ON couriers, assignment_offers, outbox_events TO fleet_app;
GRANT UPDATE (status, active_shipment_id, fence_token, updated_at) ON couriers TO fleet_app;
GRANT UPDATE (status, decided_at) ON assignment_offers TO fleet_app;
GRANT DELETE ON outbox_events TO fleet_app;
