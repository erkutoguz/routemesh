CREATE TABLE payment_statuses (
    code     text    PRIMARY KEY,
    terminal boolean NOT NULL
);

CREATE TABLE payment_transitions (
    from_status text NOT NULL REFERENCES payment_statuses (code),
    to_status   text NOT NULL REFERENCES payment_statuses (code),
    PRIMARY KEY (from_status, to_status)
);

CREATE TABLE payment_intents (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    shipment_id     uuid        NOT NULL,
    amount_minor    bigint      NOT NULL,
    currency        char(3)     NOT NULL,
    status          text        NOT NULL REFERENCES payment_statuses (code),
    psp_payment_id  text        NOT NULL UNIQUE,
    checkout_url    text        NOT NULL,
    captured_minor  bigint      NOT NULL DEFAULT 0,
    refunded_minor  bigint      NOT NULL DEFAULT 0,
    failure_reason  text,
    version         bigint      NOT NULL DEFAULT 0,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_intents_amount_check CHECK (amount_minor > 0),
    CONSTRAINT payment_intents_currency_check CHECK (currency = 'TRY'),
    CONSTRAINT payment_intents_captured_check CHECK (captured_minor BETWEEN 0 AND amount_minor),
    CONSTRAINT payment_intents_refunded_check CHECK (refunded_minor BETWEEN 0 AND captured_minor)
);

CREATE INDEX payment_intents_shipment_idx ON payment_intents (shipment_id);
CREATE INDEX payment_intents_expiry_idx ON payment_intents (expires_at) WHERE status = 'CREATED';

CREATE TABLE payment_transactions (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id     uuid        NOT NULL REFERENCES payment_intents (id),
    type          text        NOT NULL,
    command_key   text        NOT NULL,
    request_hash  text        NOT NULL,
    amount_minor  bigint      NOT NULL,
    status        text        NOT NULL,
    reason        text,
    psp_reference text,
    last_error    text,
    attempts      integer     NOT NULL DEFAULT 0,
    claimed_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_transactions_command_unique UNIQUE (intent_id, type, command_key),
    CONSTRAINT payment_transactions_type_check CHECK (type IN ('CAPTURE', 'VOID', 'REFUND')),
    CONSTRAINT payment_transactions_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT payment_transactions_amount_check CHECK (amount_minor > 0)
);

CREATE INDEX payment_transactions_open_idx ON payment_transactions (updated_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN');

CREATE TABLE webhook_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    psp_event_id   text        NOT NULL UNIQUE,
    event_type     text        NOT NULL,
    psp_payment_id text,
    payload        text        NOT NULL,
    outcome        text        NOT NULL DEFAULT 'PROCESSING',
    received_at    timestamptz NOT NULL DEFAULT now()
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

CREATE TRIGGER payment_intents_no_delete BEFORE DELETE ON payment_intents
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER payment_intents_no_truncate BEFORE TRUNCATE ON payment_intents
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER payment_transactions_no_delete BEFORE DELETE ON payment_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER payment_transactions_no_truncate BEFORE TRUNCATE ON payment_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER webhook_events_no_delete BEFORE DELETE ON webhook_events
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER webhook_events_no_truncate BEFORE TRUNCATE ON webhook_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE FUNCTION enforce_payment_transition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT EXISTS (
        SELECT 1 FROM payment_transitions
        WHERE from_status = OLD.status AND to_status = NEW.status
    ) THEN
        RAISE EXCEPTION 'payment transition from % to % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_intents_enforce_transition BEFORE UPDATE OF status ON payment_intents
    FOR EACH ROW EXECUTE FUNCTION enforce_payment_transition();

GRANT USAGE ON SCHEMA public TO payment_app;
GRANT SELECT ON payment_statuses, payment_transitions TO payment_app;
GRANT SELECT, INSERT ON payment_intents, payment_transactions, webhook_events TO payment_app;
GRANT UPDATE (status, captured_minor, refunded_minor, failure_reason, version, updated_at)
    ON payment_intents TO payment_app;
GRANT UPDATE (status, psp_reference, last_error, attempts, claimed_at, updated_at)
    ON payment_transactions TO payment_app;
GRANT UPDATE (outcome) ON webhook_events TO payment_app;
GRANT SELECT, INSERT, DELETE ON outbox_events TO payment_app;
GRANT SELECT ON outbox_events TO debezium;

CREATE PUBLICATION outbox_publication FOR TABLE outbox_events WITH (publish = 'insert');
