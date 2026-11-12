CREATE TABLE reconciliation_runs (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_source    text        NOT NULL,
    requested_by      text,
    status            text        NOT NULL DEFAULT 'RUNNING',
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    window_from       timestamptz NOT NULL,
    window_to         timestamptz NOT NULL,
    psp_payments_seen bigint      NOT NULL DEFAULT 0,
    intents_checked   bigint      NOT NULL DEFAULT 0,
    findings_count    integer     NOT NULL DEFAULT 0,
    error             text,
    CONSTRAINT reconciliation_runs_trigger_check CHECK (trigger_source IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT reconciliation_runs_status_check CHECK (status IN ('RUNNING', 'OK', 'DISCREPANCIES', 'FAILED')),
    CONSTRAINT reconciliation_runs_finished_check CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT reconciliation_runs_window_check CHECK (window_from < window_to)
);

-- Aynı anda tek koşu: çok örnekli çalışmada kilit görevi görür
CREATE UNIQUE INDEX reconciliation_runs_single_flight ON reconciliation_runs ((true)) WHERE status = 'RUNNING';
CREATE INDEX reconciliation_runs_started_idx ON reconciliation_runs (started_at DESC, id DESC);
CREATE INDEX reconciliation_runs_window_idx ON reconciliation_runs (window_to DESC)
    WHERE status IN ('OK', 'DISCREPANCIES');

CREATE TABLE reconciliation_findings (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id               uuid        NOT NULL REFERENCES reconciliation_runs (id),
    type                 text        NOT NULL,
    severity             text        NOT NULL,
    intent_id            uuid,
    shipment_id          uuid,
    psp_payment_id       text,
    transaction_id       uuid,
    local_status         text,
    psp_status           text,
    local_captured_minor bigint,
    local_refunded_minor bigint,
    psp_captured_minor   bigint,
    psp_refunded_minor   bigint,
    detail               jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reconciliation_findings_severity_check CHECK (severity IN ('CRITICAL', 'WARNING'))
);

CREATE INDEX reconciliation_findings_run_idx ON reconciliation_findings (run_id, id DESC);
CREATE INDEX reconciliation_findings_type_idx ON reconciliation_findings (type, id DESC);
CREATE INDEX reconciliation_findings_psp_idx ON reconciliation_findings (psp_payment_id, id DESC)
    WHERE psp_payment_id IS NOT NULL;

CREATE TRIGGER reconciliation_findings_immutable BEFORE UPDATE OR DELETE ON reconciliation_findings
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER reconciliation_findings_no_truncate BEFORE TRUNCATE ON reconciliation_findings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER reconciliation_runs_no_delete BEFORE DELETE ON reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER reconciliation_runs_no_truncate BEFORE TRUNCATE ON reconciliation_runs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

GRANT SELECT, INSERT ON reconciliation_runs, reconciliation_findings TO payment_app;
GRANT UPDATE (status, finished_at, psp_payments_seen, intents_checked, findings_count, error)
    ON reconciliation_runs TO payment_app;
