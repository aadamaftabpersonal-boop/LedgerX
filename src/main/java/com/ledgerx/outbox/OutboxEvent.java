package com.ledgerx.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code payload} is the JSON body that is delivered to consumers. {@code id} doubles as the event id for deduplication. */
public record OutboxEvent(
        long id,
        String eventType,
        UUID aggregateId,
        String payload,
        int attempts,
        OffsetDateTime createdAt) {}
