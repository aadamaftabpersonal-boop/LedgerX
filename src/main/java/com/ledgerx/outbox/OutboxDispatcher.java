package com.ledgerx.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers outbox events. One batch runs in one transaction: the claimed rows stay locked while they are published,
 * and a failure of one event never blocks the others. The trade-off is that a slow consumer holds row locks for up
 * to batchSize x request timeout, which is why the batch is small and the webhook timeout is short.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final int batchSize;
    private final int maxAttempts;
    private final Counter delivered;
    private final Counter failed;

    public OutboxDispatcher(
            OutboxRepository repository,
            EventPublisher publisher,
            MeterRegistry registry,
            @Value("${ledgerx.outbox.batch-size:20}") int batchSize,
            @Value("${ledgerx.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.delivered = Counter.builder("ledgerx.outbox.delivered").description("Events delivered").register(registry);
        this.failed = Counter.builder("ledgerx.outbox.failed").description("Failed delivery attempts").register(registry);
        Gauge.builder("ledgerx.outbox.pending", repository, OutboxRepository::pendingCount)
                .description("Events waiting for delivery").register(registry);
        Gauge.builder("ledgerx.outbox.dead", repository, OutboxRepository::deadCount)
                .description("Events that exhausted their retries").register(registry);
    }

    /** Returns how many events were attempted. */
    @Transactional
    public int dispatchBatch() {
        List<OutboxEvent> batch = repository.claimBatch(batchSize);
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                repository.markDelivered(event.id());
                delivered.increment();
            } catch (Exception e) {
                int attempts = event.attempts() + 1;
                boolean dead = attempts >= maxAttempts;
                repository.markFailed(event.id(), attempts, backoffSeconds(attempts), dead, abbreviate(e));
                failed.increment();
                if (dead) {
                    log.error("outbox event {} is dead after {} attempts: {}", event.id(), attempts, e.toString());
                } else {
                    log.warn("outbox event {} failed (attempt {}): {}", event.id(), attempts, e.toString());
                }
            }
        }
        return batch.size();
    }

    /** 2, 4, 8, ... seconds, capped at 5 minutes. */
    static long backoffSeconds(int attempts) {
        return Math.min(300L, 1L << Math.min(attempts, 20));
    }

    private static String abbreviate(Exception e) {
        String text = e.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
