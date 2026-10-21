package com.ledgerx.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the same transaction as the change it describes. */
    public void insert(String eventType, UUID aggregateId, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox_events (event_type, aggregate_id, payload)
                VALUES (:type, :aggregate, CAST(:payload AS jsonb))
                """)
                .param("type", eventType)
                .param("aggregate", aggregateId)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims due events and row-locks them until the surrounding transaction ends. SKIP LOCKED lets several
     * dispatchers (threads or application instances) work in parallel without ever delivering the same row twice.
     */
    public List<OutboxEvent> claimBatch(int limit) {
        return jdbc.sql("""
                SELECT id, event_type, aggregate_id, payload::text AS payload, attempts, created_at
                FROM outbox_events
                WHERE delivered_at IS NULL AND NOT dead AND next_attempt_at <= now()
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(OutboxEvent.class)
                .list();
    }

    public void markDelivered(long id) {
        jdbc.sql("UPDATE outbox_events SET delivered_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markFailed(long id, int attempts, long retryAfterSeconds, boolean dead, String error) {
        jdbc.sql("""
                UPDATE outbox_events
                SET attempts = :attempts, dead = :dead, last_error = :error,
                    next_attempt_at = now() + make_interval(secs => :delay)
                WHERE id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("dead", dead)
                .param("error", error)
                .param("delay", retryAfterSeconds)
                .update();
    }

    public long pendingCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE delivered_at IS NULL AND NOT dead")
                .query(Long.class).single();
    }

    public long deadCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE dead")
                .query(Long.class).single();
    }

    /** Moves dead events back into the queue, e.g. after the consumer's outage is over. */
    public int requeueDead() {
        return jdbc.sql("""
                UPDATE outbox_events SET dead = FALSE, attempts = 0, next_attempt_at = now()
                WHERE dead
                """).update();
    }
}
