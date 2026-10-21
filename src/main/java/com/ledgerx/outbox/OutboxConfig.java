package com.ledgerx.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutboxConfig {

    private static final Logger log = LoggerFactory.getLogger("ledgerx.events");

    /**
     * Webhook delivery when ledgerx.outbox.webhook-url is set and non-blank (an empty value, as docker compose
     * passes by default, means "not configured"); otherwise each event is written to the log, so the pipeline
     * works end to end with no external consumer.
     */
    @Bean
    EventPublisher eventPublisher(
            @Value("${ledgerx.outbox.webhook-url:}") String url,
            @Value("${ledgerx.outbox.webhook-secret:}") String secret) {
        if (url.isBlank()) {
            return event -> log.info("event id={} type={} payload={}", event.id(), event.eventType(), event.payload());
        }
        return new WebhookEventPublisher(url.trim(), secret);
    }
}
