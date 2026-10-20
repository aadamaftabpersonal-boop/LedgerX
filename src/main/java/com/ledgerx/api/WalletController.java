package com.ledgerx.api;

import com.ledgerx.client.Client;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferException;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    public record CreateWalletRequest(
            @NotBlank String owner,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter currency code") String currency) {}

    public record DepositRequest(@Positive long amount) {}

    private final WalletRepository wallets;
    private final TransferService transfers;

    public WalletController(WalletRepository wallets, TransferService transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<Wallet> create(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @Valid @RequestBody CreateWalletRequest request) {
        // Fails fast on unsupported currencies instead of creating a wallet nobody can fund.
        wallets.externalWalletId(request.currency());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wallets.create(client.id(), request.owner(), request.currency()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Wallet> get(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client, @PathVariable UUID id) {
        return wallets.findOwned(id, client.id()).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<Transfer> deposit(
            @RequestAttribute(ApiKeyAuthFilter.CLIENT_ATTRIBUTE) Client client,
            @PathVariable UUID id,
            @Valid @RequestBody DepositRequest request) {
        wallets.findOwned(id, client.id())
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + id));
        return ResponseEntity.status(HttpStatus.CREATED).body(transfers.deposit(id, request.amount()));
    }
}
