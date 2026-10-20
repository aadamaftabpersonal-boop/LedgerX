package com.ledgerx.api;

import com.ledgerx.client.ClientRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Bootstraps API clients. Disabled unless ledgerx.admin-key (env LEDGERX_ADMIN_KEY) is set. */
@RestController
@RequestMapping("/admin/clients")
public class AdminController {

    public record CreateClientRequest(@NotBlank String name) {}

    public record ClientCreated(UUID id, String name, String apiKey) {}

    private final ClientRepository clients;
    private final String adminKey;

    public AdminController(ClientRepository clients, @Value("${ledgerx.admin-key:}") String adminKey) {
        this.clients = clients;
        this.adminKey = adminKey;
    }

    /** The API key is returned once and cannot be retrieved again. */
    @PostMapping
    public ResponseEntity<ClientCreated> create(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            @Valid @RequestBody CreateClientRequest request) {
        if (adminKey.isBlank() || providedKey == null || !constantTimeEquals(adminKey, providedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid admin key");
        }
        ClientRepository.Created created = clients.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientCreated(created.client().id(), created.client().name(), created.apiKey()));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
