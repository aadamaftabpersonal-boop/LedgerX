package com.ledgerx.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code reversesTransferId} is set when this transfer is a reversal (refund) of an earlier one. */
public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amount,
        String currency,
        UUID reversesTransferId,
        OffsetDateTime createdAt) {}
