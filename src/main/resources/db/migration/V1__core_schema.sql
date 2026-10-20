-- Money is stored as integer minor units (paise, cents). Never floating point.

CREATE TABLE wallets (
    id         UUID PRIMARY KEY,
    owner      TEXT        NOT NULL,
    currency   CHAR(3)     NOT NULL,
    kind       TEXT        NOT NULL DEFAULT 'USER' CHECK (kind IN ('USER', 'EXTERNAL')),
    balance    BIGINT      NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- User wallets can never go negative. EXTERNAL wallets are the counterparty for deposits.
    CONSTRAINT wallets_non_negative CHECK (kind = 'EXTERNAL' OR balance >= 0)
);

CREATE TABLE transfers (
    id             UUID PRIMARY KEY,
    from_wallet_id UUID        NOT NULL REFERENCES wallets (id),
    to_wallet_id   UUID        NOT NULL REFERENCES wallets (id),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id)
);

-- Double-entry ledger: each transfer writes one negative (debit) and one positive (credit) entry.
CREATE TABLE ledger_entries (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id UUID        NOT NULL REFERENCES transfers (id),
    wallet_id   UUID        NOT NULL REFERENCES wallets (id),
    amount      BIGINT      NOT NULL CHECK (amount <> 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_wallet ON ledger_entries (wallet_id);
CREATE INDEX idx_ledger_transfer ON ledger_entries (transfer_id);

-- The ledger is append-only.
CREATE FUNCTION ledger_entries_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_no_update
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_immutable();

-- One EXTERNAL counterparty wallet per supported currency.
INSERT INTO wallets (id, owner, currency, kind) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'external-inr', 'INR', 'EXTERNAL'),
    ('00000000-0000-0000-0000-0000000000a2', 'external-usd', 'USD', 'EXTERNAL'),
    ('00000000-0000-0000-0000-0000000000a3', 'external-eur', 'EUR', 'EXTERNAL');
