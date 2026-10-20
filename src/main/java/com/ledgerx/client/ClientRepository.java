package com.ledgerx.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientRepository {

    /** The plaintext key exists only in this object; the database stores its hash. */
    public record Created(Client client, String apiKey) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public ClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Created create(String name) {
        UUID id = UUID.randomUUID();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String apiKey = "lx_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("INSERT INTO api_clients (id, name, api_key_hash) VALUES (:id, :name, :hash)")
                .param("id", id)
                .param("name", name)
                .param("hash", hash(apiKey))
                .update();
        Client client = jdbc.sql("SELECT id, name, created_at FROM api_clients WHERE id = :id")
                .param("id", id)
                .query(Client.class)
                .single();
        return new Created(client, apiKey);
    }

    public Optional<Client> findByApiKey(String apiKey) {
        return jdbc.sql("SELECT id, name, created_at FROM api_clients WHERE api_key_hash = :hash")
                .param("hash", hash(apiKey))
                .query(Client.class)
                .optional();
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
