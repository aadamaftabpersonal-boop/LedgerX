package com.ledgerx.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Checks the X-Admin-Key header. Every admin endpoint is disabled while ledgerx.admin-key is empty. */
@Component
public class AdminKeyGuard {

    private final String adminKey;

    public AdminKeyGuard(@Value("${ledgerx.admin-key:}") String adminKey) {
        this.adminKey = adminKey;
    }

    public void require(String providedKey) {
        if (adminKey.isBlank() || providedKey == null || !constantTimeEquals(adminKey, providedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid admin key");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
