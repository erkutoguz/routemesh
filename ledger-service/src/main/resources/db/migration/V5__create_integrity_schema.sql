CREATE TABLE integrity_runs (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    mode            text        NOT NULL,
    trigger_source  text        NOT NULL,
    requested_by    text,
    status          text        NOT NULL DEFAULT 'RUNNING',
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    from_entry_id   bigint,
    to_entry_id     bigint,
    entries_scanned bigint      NOT NULL DEFAULT 0,
    findings_count  integer     NOT NULL DEFAULT 0,
    error           text,
    CONSTRAINT integrity_runs_mode_check CHECK (mode IN ('INCREMENTAL', 'FULL')),
    CONSTRAINT integrity_runs_trigger_check CHECK (trigger_source IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT integrity_runs_status_check CHECK (status IN ('RUNNING', 'OK', 'VIOLATIONS', 'FAILED')),
    CONSTRAINT integrity_runs_finished_check CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

-- Aynı modda aynı anda tek koşu: çok örnekli çalışmada kilit görevi görür
CREATE UNIQUE INDEX integrity_runs_single_flight ON integrity_runs (mode) WHERE status = 'RUNNING';
CREATE INDEX integrity_runs_started_idx ON integrity_runs (started_at DESC, id DESC);

CREATE TABLE integrity_findings (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         uuid        NOT NULL REFERENCES integrity_runs (id),
    type           text        NOT NULL,
    severity       text        NOT NULL,
    account_code   text,
    shipment_id    uuid,
    transaction_id uuid,
    expected_minor bigint,
    actual_minor   bigint,
    detail         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integrity_findings_severity_check CHECK (severity IN ('CRITICAL', 'WARNING'))
);

CREATE INDEX integrity_findings_run_idx ON integrity_findings (run_id, id DESC);
CREATE INDEX integrity_findings_type_idx ON integrity_findings (type, id DESC);

-- Her satır, o ana kadarki tüm kayıtların denetlenmiş özeti. Zincir hash'i önceki satırın hash'ine bağlıdır.
CREATE TABLE ledger_checkpoints (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id             uuid        NOT NULL REFERENCES integrity_runs (id),
    taken_at           timestamptz NOT NULL DEFAULT now(),
    last_entry_id      bigint      NOT NULL UNIQUE,
    entry_count        bigint      NOT NULL,
    total_debit_minor  bigint      NOT NULL,
    total_credit_minor bigint      NOT NULL,
    chain_hash         text        NOT NULL,
    CONSTRAINT ledger_checkpoints_hash_check CHECK (chain_hash ~ '^[0-9a-f]{64}$')
);

-- En son checkpoint'e karşılık gelen bakiyeler (yeniden kurulabilir önbellek, gerçek kaynak ledger_entries).
-- scope_shipment_id yalnızca LIABILITY:ESCROW için gönderi kimliğidir, diğer hesaplarda sıfır UUID.
CREATE TABLE ledger_balance_cache (
    account_id        uuid   NOT NULL REFERENCES accounts (id),
    scope_shipment_id uuid   NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    net_debit_minor   bigint NOT NULL,
    PRIMARY KEY (account_id, scope_shipment_id)
);

CREATE TRIGGER integrity_findings_immutable BEFORE UPDATE OR DELETE ON integrity_findings
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER integrity_findings_no_truncate BEFORE TRUNCATE ON integrity_findings
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER ledger_checkpoints_immutable BEFORE UPDATE OR DELETE ON ledger_checkpoints
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER ledger_checkpoints_no_truncate BEFORE TRUNCATE ON ledger_checkpoints
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER integrity_runs_no_delete BEFORE DELETE ON integrity_runs
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER integrity_runs_no_truncate BEFORE TRUNCATE ON integrity_runs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

GRANT SELECT, INSERT ON integrity_findings, ledger_checkpoints TO ledger_app;
GRANT SELECT, INSERT ON integrity_runs TO ledger_app;
GRANT UPDATE (status, finished_at, from_entry_id, to_entry_id, entries_scanned, findings_count, error)
    ON integrity_runs TO ledger_app;
GRANT SELECT, INSERT, UPDATE (net_debit_minor), DELETE ON ledger_balance_cache TO ledger_app;
