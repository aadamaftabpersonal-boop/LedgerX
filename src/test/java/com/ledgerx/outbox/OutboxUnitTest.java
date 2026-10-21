package com.ledgerx.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxUnitTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> signature = new AtomicReference<>();
    private final AtomicReference<String> eventId = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("X-Ledgerx-Signature"));
            eventId.set(exchange.getRequestHeaders().getFirst("X-Ledgerx-Event-Id"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private OutboxEvent event() {
        return new OutboxEvent(42, "transfer.completed", UUID.randomUUID(), "{\"amount\":100}", 0, OffsetDateTime.now());
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void webhookPostsTheSignedPayloadWithTheEventId() throws Exception {
        new WebhookEventPublisher(url(), "s3cret").publish(event());

        assertThat(body.get()).isEqualTo("{\"amount\":100}");
        assertThat(eventId.get()).isEqualTo("42");
        assertThat(signature.get()).isEqualTo("sha256=" + hmac("s3cret", "{\"amount\":100}"));
    }

    @Test
    void aDifferentSecretProducesADifferentSignature() throws Exception {
        new WebhookEventPublisher(url(), "other-secret").publish(event());

        assertThat(signature.get()).isNotEqualTo("sha256=" + hmac("s3cret", "{\"amount\":100}"));
    }

    @Test
    void nonSuccessResponsesAreFailures() {
        status = 503;

        assertThatThrownBy(() -> new WebhookEventPublisher(url(), "s").publish(event()))
                .isInstanceOf(IOException.class).hasMessageContaining("503");
    }

    @Test
    void unreachableConsumersAreFailures() {
        server.stop(0);

        assertThatThrownBy(() -> new WebhookEventPublisher(url(), "s").publish(event()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aBlankWebhookUrlMeansLogOnlyNotAWebhook() {
        // docker compose passes an empty string when no webhook is configured; that must not create a webhook publisher.
        assertThat(new OutboxConfig().eventPublisher("", "")).isNotInstanceOf(WebhookEventPublisher.class);
        assertThat(new OutboxConfig().eventPublisher("   ", "secret")).isNotInstanceOf(WebhookEventPublisher.class);
        assertThat(new OutboxConfig().eventPublisher("http://127.0.0.1:1/hook", "secret"))
                .isInstanceOf(WebhookEventPublisher.class);
    }

    @Test
    void logOnlyPublisherAcceptsEvents() {
        assertThatCode(() -> new OutboxConfig().eventPublisher("", "").publish(event())).doesNotThrowAnyException();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(OutboxDispatcher.backoffSeconds(1)).isEqualTo(2);
        assertThat(OutboxDispatcher.backoffSeconds(2)).isEqualTo(4);
        assertThat(OutboxDispatcher.backoffSeconds(5)).isEqualTo(32);
        assertThat(OutboxDispatcher.backoffSeconds(9)).isEqualTo(300);
        assertThat(OutboxDispatcher.backoffSeconds(100)).isEqualTo(300);
    }
}
