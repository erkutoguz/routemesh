CREATE TABLE accounts (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text        NOT NULL UNIQUE,
    type       text        NOT NULL,
    currency   char(3)     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT accounts_type_check CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT accounts_currency_check CHECK (currency = 'TRY'),
    CONSTRAINT accounts_code_prefix_check CHECK (code LIKE type || ':%')
);

CREATE TABLE journal_transactions (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  text        NOT NULL UNIQUE,
    request_hash     text        NOT NULL,
    transaction_type text        NOT NULL,
    shipment_id      uuid,
    reversal_of      uuid        UNIQUE REFERENCES journal_transactions (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT journal_transactions_key_check CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT journal_transactions_type_check CHECK (length(btrim(transaction_type)) > 0)
);

CREATE TABLE ledger_entries (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id uuid        NOT NULL REFERENCES journal_transactions (id),
    account_id     uuid        NOT NULL REFERENCES accounts (id),
    direction      text        NOT NULL,
    amount_minor   bigint      NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_check CHECK (amount_minor > 0)
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);
CREATE INDEX journal_transactions_shipment_idx ON journal_transactions (shipment_id) WHERE shipment_id IS NOT NULL;

CREATE FUNCTION forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: ledger records are immutable', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER accounts_immutable BEFORE UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER accounts_no_truncate BEFORE TRUNCATE ON accounts
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER journal_transactions_immutable BEFORE UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER journal_transactions_no_truncate BEFORE TRUNCATE ON journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE FUNCTION check_transaction_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    difference bigint;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
    INTO difference
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    IF difference <> 0 THEN
        RAISE EXCEPTION 'transaction % is not balanced (debit minus credit = %)', NEW.transaction_id, difference
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

CREATE FUNCTION check_transaction_has_entries() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ledger_entries WHERE transaction_id = NEW.id) THEN
        RAISE EXCEPTION 'transaction % has no entries', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_transactions_has_entries
    AFTER INSERT ON journal_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_has_entries();

GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT, INSERT ON accounts, journal_transactions, ledger_entries TO ledger_app;
