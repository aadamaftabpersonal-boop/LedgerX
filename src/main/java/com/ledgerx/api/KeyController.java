package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.client.ClientRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Lets a client manage its own API keys without involving an administrator. */
@Tag(name = "API keys")
@RestController
@RequestMapping("/keys")
public class KeyController {

    private static final long DEFAULT_GRACE_SECONDS = 3600;

    public record RotateRequest(@Min(0) @Max(604_800) Long gracePeriodSeconds) {}

    public record RotateResponse(String apiKey, OffsetDateTime previousKeysExpireAt) {}

    private final ClientRepository clients;

    public KeyController(ClientRepository clients) {
        this.clients = clients;
    }

    @Operation(summary = "Rotate your API key without downtime",
            description = "Returns a new key (shown once). Existing keys keep working for the grace period "
                    + "(default 1 hour, maximum 7 days, 0 = immediately).")
    @PostMapping("/rotate")
    public ResponseEntity<RotateResponse> rotate(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @Valid @RequestBody(required = false) RotateRequest request) {
        long grace = request == null || request.gracePeriodSeconds() == null
                ? DEFAULT_GRACE_SECONDS : request.gracePeriodSeconds();
        ClientRepository.Rotated rotated = clients.rotate(client.id(), grace);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RotateResponse(rotated.apiKey(), rotated.previousKeysExpireAt()));
    }

    @Operation(summary = "List your keys (metadata only, never secrets)")
    @GetMapping
    public List<ClientRepository.KeyInfo> list(@RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client) {
        return clients.list(client.id());
    }

    @Operation(summary = "Revoke a key immediately", description = "409 if it is your last active key.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client, @PathVariable UUID id) {
        return switch (clients.revoke(client.id(), id)) {
            case REVOKED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found");
            case LAST_ACTIVE_KEY -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Cannot revoke the last active key; rotate first");
        };
    }
}
