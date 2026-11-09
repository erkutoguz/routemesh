CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    type           text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, DELETE ON outbox_events TO ledger_app;
GRANT SELECT ON outbox_events TO debezium;

CREATE PUBLICATION outbox_publication FOR TABLE outbox_events WITH (publish = 'insert');
