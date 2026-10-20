package com.ledgerx.wallet;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Balance is in minor units (e.g. paise). */
public record Wallet(
        UUID id,
        UUID clientId,
        String owner,
        String currency,
        String kind,
        long balance,
        long version,
        OffsetDateTime createdAt) {

    public boolean isExternal() {
        return "EXTERNAL".equals(kind);
    }
}
