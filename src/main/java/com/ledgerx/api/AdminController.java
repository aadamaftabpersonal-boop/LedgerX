package com.ledgerx.api;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.reconciliation.ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints, protected by X-Admin-Key instead of a client API key. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    public record CreateClientRequest(@NotBlank String name) {}

    public record ClientCreated(UUID id, String name, String apiKey) {}

    private final ClientRepository clients;
    private final ReconciliationService reconciliation;
    private final AdminKeyGuard guard;

    public AdminController(ClientRepository clients, ReconciliationService reconciliation, AdminKeyGuard guard) {
        this.clients = clients;
        this.reconciliation = reconciliation;
        this.guard = guard;
    }

    /** The API key is returned once and cannot be retrieved again. */
    @PostMapping("/clients")
    public ResponseEntity<ClientCreated> createClient(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            @Valid @RequestBody CreateClientRequest request) {
        guard.require(providedKey);
        ClientRepository.Created created = clients.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientCreated(created.client().id(), created.client().name(), created.apiKey()));
    }

    /** Runs the ledger audit now and returns what it found. */
    @GetMapping("/reconciliation")
    public ReconciliationService.Report reconcile(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {
        guard.require(providedKey);
        return reconciliation.run();
    }
}
