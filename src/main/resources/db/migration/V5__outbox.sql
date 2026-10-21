-- Transactional outbox: an event row is written in the same transaction as the money movement,
-- so an event exists if and only if the transfer committed. A dispatcher delivers rows afterwards.
CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    event_type      TEXT        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    dead            BOOLEAN     NOT NULL DEFAULT FALSE,
    last_error      TEXT
);

-- The dispatcher only ever looks at undelivered, live rows.
CREATE INDEX idx_outbox_pending ON outbox_events (next_attempt_at)
    WHERE delivered_at IS NULL AND NOT dead;
