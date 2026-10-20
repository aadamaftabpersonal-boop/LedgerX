-- API clients authenticate with a key; only the SHA-256 hash of the key is stored.
CREATE TABLE api_clients (
    id           UUID PRIMARY KEY,
    name         TEXT        NOT NULL,
    api_key_hash TEXT        NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- EXTERNAL wallets have no owner, so this stays nullable.
ALTER TABLE wallets ADD COLUMN client_id UUID REFERENCES api_clients (id);
CREATE INDEX idx_wallets_client ON wallets (client_id);

-- Idempotency keys are now scoped per client, so two clients can use the same key string.
-- The table only holds short-lived retry records, so it is recreated instead of migrated.
DROP TABLE idempotency_keys;
CREATE TABLE idempotency_keys (
    client_id    UUID        NOT NULL REFERENCES api_clients (id),
    key          TEXT        NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
    request_hash TEXT        NOT NULL,
    transfer_id  UUID REFERENCES transfers (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, key)
);
