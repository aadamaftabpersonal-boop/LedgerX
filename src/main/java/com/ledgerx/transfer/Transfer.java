package com.ledgerx.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        String currency,
        OffsetDateTime createdAt) {}
