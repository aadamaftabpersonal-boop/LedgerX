package com.ledgerx.api;

import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping
    public ResponseEntity<Transfer> create(@Valid @RequestBody TransferRequest request) {
        Transfer transfer = transfers.transfer(request.fromWalletId(), request.toWalletId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(transfer);
    }
}
