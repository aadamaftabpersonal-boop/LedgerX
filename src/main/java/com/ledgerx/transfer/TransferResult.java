package com.ledgerx.transfer;

/** {@code replayed} is true when an earlier request with the same idempotency key already did the work. */
public record TransferResult(Transfer transfer, boolean replayed) {}
