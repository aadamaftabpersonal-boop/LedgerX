package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferResult;
import com.ledgerx.transfer.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/transfers")
public class TransferController {

    public record TransferRequest(
            @NotNull UUID fromWalletId,
            @NotNull UUID toWalletId,
            @Positive long amount) {}

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /** 201 for a new transfer, 200 plus Idempotent-Replayed: true when the key was already used. */
    @PostMapping
    public ResponseEntity<Transfer> create(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transfers.transfer(
                client.id(), idempotencyKey, request.fromWalletId(), request.toWalletId(), request.amount());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.transfer());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transfer());
    }
}
