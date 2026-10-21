package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferResult;
import com.ledgerx.transfer.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfers")
@Validated
@RestController
@RequestMapping("/transfers")
public class TransferController {

    public record TransferRequest(
            @NotNull UUID fromWalletId,
            @NotNull UUID toWalletId,
            @Positive long amount) {}

    private final TransferService transfers;
    private final TransferMetrics metrics;

    public TransferController(TransferService transfers, TransferMetrics metrics) {
        this.transfers = transfers;
        this.metrics = metrics;
    }

    @Operation(summary = "Move money from one of your wallets to any wallet",
            description = "Idempotent: 201 for a new transfer, 200 with header Idempotent-Replayed: true when the key was already used, "
                    + "409 when the key was used for a different request, 422 for insufficient funds.")
    @PostMapping
    public ResponseEntity<Transfer> create(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = metrics.observe(() -> transfers.transfer(
                client.id(), idempotencyKey, request.fromWalletId(), request.toWalletId(), request.amount()));
        return toResponse(result);
    }

    @Operation(summary = "Refund a transfer you received",
            description = "Moves the money back through a compensating transfer; the original is never edited. A transfer can be "
                    + "reversed once (409 afterwards). Deposits and reversals cannot be reversed (400). 422 if the funds are gone.")
    @PostMapping("/{id}/reverse")
    public ResponseEntity<Transfer> reverse(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @PathVariable UUID id) {
        return toResponse(metrics.observe(() -> transfers.reverse(client.id(), idempotencyKey, id)));
    }

    @Operation(summary = "Read a transfer", description = "Visible to the owners of the source and destination wallets only.")
    @GetMapping("/{id}")
    public Transfer get(@RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client, @PathVariable UUID id) {
        return transfers.findVisible(client.id(), id);
    }

    private static ResponseEntity<Transfer> toResponse(TransferResult result) {
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.transfer());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transfer());
    }
}
