package com.ledgerx.outbox;

/**
 * Delivers one event to the outside world. Throw to signal failure; the dispatcher retries with backoff.
 * Delivery is at-least-once, so consumers must deduplicate on {@link OutboxEvent#id()}.
 */
public interface EventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
