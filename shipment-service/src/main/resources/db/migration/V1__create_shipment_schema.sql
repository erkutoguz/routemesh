CREATE TABLE shipment_statuses (
    code     text    PRIMARY KEY,
    terminal boolean NOT NULL
);

CREATE TABLE shipment_transitions (
    from_status  text    NOT NULL REFERENCES shipment_statuses (code),
    to_status    text    NOT NULL REFERENCES shipment_statuses (code),
    actor        text    NOT NULL,
    requires_pod boolean NOT NULL DEFAULT false,
    PRIMARY KEY (from_status, to_status, actor),
    CONSTRAINT shipment_transitions_actor_check
        CHECK (actor IN ('SYSTEM', 'MERCHANT', 'COURIER', 'WAREHOUSE', 'OPS'))
);

CREATE TABLE shipments (
    id                 uuid             PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key    text             NOT NULL UNIQUE,
    request_hash       text             NOT NULL,
    merchant_id        uuid             NOT NULL,
    status             text             NOT NULL REFERENCES shipment_statuses (code),
    courier_id         uuid,
    version            bigint           NOT NULL DEFAULT 0,
    recipient_name     text             NOT NULL,
    recipient_phone    text             NOT NULL,
    delivery_address   text             NOT NULL,
    delivery_latitude  double precision NOT NULL,
    delivery_longitude double precision NOT NULL,
    quote_id           uuid             NOT NULL,
    final_amount_minor bigint           NOT NULL,
    base_price_minor   bigint           NOT NULL,
    currency           char(3)          NOT NULL,
    created_at         timestamptz      NOT NULL DEFAULT now(),
    updated_at         timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT shipments_amounts_check CHECK (final_amount_minor > 0 AND base_price_minor > 0),
    CONSTRAINT shipments_currency_check CHECK (currency = 'TRY'),
    CONSTRAINT shipments_latitude_check CHECK (delivery_latitude BETWEEN -90 AND 90),
    CONSTRAINT shipments_longitude_check CHECK (delivery_longitude BETWEEN -180 AND 180),
    CONSTRAINT shipments_courier_assigned_check
        CHECK (status NOT IN ('ASSIGNED', 'OUT_FOR_DELIVERY') OR courier_id IS NOT NULL),
    CONSTRAINT shipments_courier_unassigned_check
        CHECK (status NOT IN ('PENDING_PAYMENT', 'PAID', 'AT_WAREHOUSE', 'READY_FOR_DISPATCH')
            OR courier_id IS NULL)
);

CREATE INDEX shipments_merchant_idx ON shipments (merchant_id, created_at DESC);
CREATE INDEX shipments_status_idx ON shipments (status);

CREATE TABLE shipment_status_history (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_id    uuid        NOT NULL REFERENCES shipments (id),
    transition_key text        NOT NULL,
    request_hash   text        NOT NULL,
    from_status    text        REFERENCES shipment_statuses (code),
    to_status      text        NOT NULL REFERENCES shipment_statuses (code),
    actor          text        NOT NULL,
    courier_id     uuid,
    reason         text,
    version        bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shipment_status_history_key_unique UNIQUE (shipment_id, transition_key),
    CONSTRAINT shipment_status_history_actor_check
        CHECK (actor IN ('SYSTEM', 'MERCHANT', 'COURIER', 'WAREHOUSE', 'OPS'))
);

CREATE INDEX shipment_status_history_shipment_idx ON shipment_status_history (shipment_id, id);

CREATE TABLE proof_of_delivery (
    shipment_id uuid        PRIMARY KEY REFERENCES shipments (id),
    recorded_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    type           text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER shipment_status_history_immutable BEFORE UPDATE OR DELETE ON shipment_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER shipment_status_history_no_truncate BEFORE TRUNCATE ON shipment_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER shipments_no_delete BEFORE DELETE ON shipments
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER shipments_no_truncate BEFORE TRUNCATE ON shipments
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE FUNCTION enforce_status_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT EXISTS (
        SELECT 1 FROM shipment_transitions
        WHERE from_status = OLD.status AND to_status = NEW.status
    ) THEN
        RAISE EXCEPTION 'transition from % to % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER shipments_enforce_transition BEFORE UPDATE OF status ON shipments
    FOR EACH ROW EXECUTE FUNCTION enforce_status_transition();

GRANT USAGE ON SCHEMA public TO shipment_app;
GRANT SELECT ON shipment_statuses, shipment_transitions, proof_of_delivery TO shipment_app;
GRANT SELECT, INSERT ON shipments, shipment_status_history, outbox_events TO shipment_app;
GRANT UPDATE (status, courier_id, version, updated_at) ON shipments TO shipment_app;
