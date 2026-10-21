package com.ledgerx.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * POSTs each event as JSON to a configured URL. The body is signed with HMAC-SHA256 so the receiver can verify
 * it came from LedgerX: header {@code X-Ledgerx-Signature: sha256=<hex>}. Any non-2xx response counts as a failure.
 */
public class WebhookEventPublisher implements EventPublisher {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI url;
    private final String secret;

    public WebhookEventPublisher(String url, String secret) {
        this.url = URI.create(url);
        this.secret = secret;
    }

    @Override
    public void publish(OutboxEvent event) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-Ledgerx-Event-Id", Long.toString(event.id()))
                .header("X-Ledgerx-Event-Type", event.eventType())
                .header("X-Ledgerx-Signature", "sha256=" + sign(event.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(event.payload()))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Webhook returned HTTP " + response.statusCode());
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign webhook body", e);
        }
    }
}
