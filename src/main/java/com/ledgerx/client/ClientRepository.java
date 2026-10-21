package com.ledgerx.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ClientRepository {

    /** The plaintext key exists only in this object; the database stores its hash. */
    public record Created(Client client, String apiKey) {}

    public record Rotated(String apiKey, OffsetDateTime previousKeysExpireAt) {}

    /** Metadata about a key. The secret itself is never stored or returned again. */
    public record KeyInfo(UUID id, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt,
                          boolean active) {}

    private static final String ACTIVE = "revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public ClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Created create(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO api_clients (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", name)
                .update();
        String apiKey = insertKey(id);
        Client client = jdbc.sql("SELECT id, name, created_at FROM api_clients WHERE id = :id")
                .param("id", id)
                .query(Client.class)
                .single();
        return new Created(client, apiKey);
    }

    public Optional<Client> findByApiKey(String apiKey) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.created_at
                FROM api_clients c JOIN api_keys k ON k.client_id = c.id
                WHERE k.key_hash = :hash AND k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at > now())
                """)
                .param("hash", hash(apiKey))
                .query(Client.class)
                .optional();
    }

    /**
     * Issues a new key. Every key that is currently active keeps working for {@code graceSeconds} (0 = expires
     * immediately), so callers can deploy the new key before the old one stops working.
     */
    @Transactional
    public Rotated rotate(UUID clientId, long graceSeconds) {
        OffsetDateTime cutoff = jdbc.sql("SELECT now() + make_interval(secs => :grace)")
                .param("grace", graceSeconds)
                .query(OffsetDateTime.class).single();
        // LEAST ignores NULL, so a key with no expiry gets the cutoff and one already expiring sooner keeps its date.
        jdbc.sql("UPDATE api_keys SET expires_at = LEAST(expires_at, :cutoff) WHERE client_id = :client AND " + ACTIVE)
                .param("cutoff", cutoff)
                .param("client", clientId)
                .update();
        return new Rotated(insertKey(clientId), cutoff);
    }

    public List<KeyInfo> list(UUID clientId) {
        return jdbc.sql("SELECT id, created_at, expires_at, revoked_at, (" + ACTIVE + ") AS active "
                        + "FROM api_keys WHERE client_id = :client ORDER BY created_at, id")
                .param("client", clientId)
                .query(KeyInfo.class)
                .list();
    }

    public enum RevokeResult { REVOKED, NOT_FOUND, LAST_ACTIVE_KEY }

    /** Revokes immediately. Refuses to revoke the last active key, which would lock the client out. */
    @Transactional
    public RevokeResult revoke(UUID clientId, UUID keyId) {
        // Serialise concurrent revocations for this client so two requests cannot each leave "the other" active.
        jdbc.sql("SELECT id FROM api_clients WHERE id = :id FOR UPDATE").param("id", clientId).query(UUID.class).single();
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM api_keys WHERE id = :id AND client_id = :client)")
                .param("id", keyId).param("client", clientId).query(Boolean.class).single();
        if (!exists) {
            return RevokeResult.NOT_FOUND;
        }
        int otherActive = jdbc.sql("SELECT COUNT(*) FROM api_keys WHERE client_id = :client AND id <> :id AND " + ACTIVE)
                .param("client", clientId).param("id", keyId).query(Integer.class).single();
        if (otherActive == 0) {
            return RevokeResult.LAST_ACTIVE_KEY;
        }
        jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND client_id = :client AND revoked_at IS NULL")
                .param("id", keyId).param("client", clientId).update();
        return RevokeResult.REVOKED;
    }

    private String insertKey(UUID clientId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String apiKey = "lx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO api_keys (id, client_id, key_hash) VALUES (:id, :client, :hash)")
                .param("id", UUID.randomUUID())
                .param("client", clientId)
                .param("hash", hash(apiKey))
                .update();
        return apiKey;
    }

    /** Keys are 256 random bits, so a fast unsalted hash is enough (unlike human-chosen passwords). */
    private static String hash(String apiKey) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
