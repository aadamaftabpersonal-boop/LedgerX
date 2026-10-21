-- A client can hold several keys at once, which makes rotation possible without downtime:
-- issue a new key, keep the old one alive for a grace period, then it expires on its own.
CREATE TABLE api_keys (
    id         UUID PRIMARY KEY,
    client_id  UUID        NOT NULL REFERENCES api_clients (id),
    key_hash   TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_api_keys_client ON api_keys (client_id);

-- Existing keys carry over unchanged.
INSERT INTO api_keys (id, client_id, key_hash, created_at)
SELECT gen_random_uuid(), id, api_key_hash, created_at FROM api_clients;

ALTER TABLE api_clients DROP COLUMN api_key_hash;
