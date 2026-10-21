package com.ledgerx.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Disabled in tests, which call {@link OutboxDispatcher#dispatchBatch()} themselves. */
@Component
@ConditionalOnProperty(name = "ledgerx.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxDispatcher dispatcher;

    public OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString = "${ledgerx.outbox.poll-interval:PT1S}",
            initialDelayString = "${ledgerx.outbox.initial-delay:PT5S}")
    public void poll() {
        // Drain: keep going while full batches keep coming.
        while (dispatcher.dispatchBatch() > 0) {
            // intentionally empty
        }
    }
}
