-- A key is written in the same transaction as the transfer it guards, so either both exist or neither does.
-- Failed transfers roll the key back too, which means a retry after a failure executes again.
CREATE TABLE idempotency_keys (
    key          TEXT PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 255),
    request_hash TEXT        NOT NULL,
    transfer_id  UUID REFERENCES transfers (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
