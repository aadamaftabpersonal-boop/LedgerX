package com.ledgerx.api;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.outbox.OutboxRepository;
import com.ledgerx.reconciliation.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin")
@SecurityRequirement(name = "adminKey")
@RestController
@RequestMapping("/admin")
public class AdminController {

    public record CreateClientRequest(@NotBlank String name) {}

    public record ClientCreated(UUID id, String name, String apiKey) {}

    public record OutboxStatus(long pending, long dead) {}

    public record Requeued(int requeued) {}

    private final ClientRepository clients;
    private final ReconciliationService reconciliation;
    private final OutboxRepository outbox;
    private final AdminKeyGuard guard;

    public AdminController(
            ClientRepository clients, ReconciliationService reconciliation, OutboxRepository outbox, AdminKeyGuard guard) {
        this.clients = clients;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.guard = guard;
    }

    @Operation(summary = "Create an API client", description = "The API key is returned once and cannot be retrieved again.")
    @PostMapping("/clients")
    public ResponseEntity<ClientCreated> createClient(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            @Valid @RequestBody CreateClientRequest request) {
        guard.require(providedKey);
        ClientRepository.Created created = clients.create(request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientCreated(created.client().id(), created.client().name(), created.apiKey()));
    }

    @Operation(summary = "Run the ledger audit now and return what it found")
    @GetMapping("/reconciliation")
    public ReconciliationService.Report reconcile(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {
        guard.require(providedKey);
        return reconciliation.run();
    }

    @Operation(summary = "Event delivery backlog",
            description = "pending: waiting for delivery or a retry. dead: gave up after the maximum attempts.")
    @GetMapping("/outbox")
    public OutboxStatus outboxStatus(@RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {
        guard.require(providedKey);
        return new OutboxStatus(outbox.pendingCount(), outbox.deadCount());
    }

    @Operation(summary = "Put dead events back in the queue",
            description = "Use after the consumer's outage is over. Delivery is at-least-once, so consumers must deduplicate.")
    @PostMapping("/outbox/requeue-dead")
    public Requeued requeueDead(@RequestHeader(value = "X-Admin-Key", required = false) String providedKey) {
        guard.require(providedKey);
        return new Requeued(outbox.requeueDead());
    }
}
