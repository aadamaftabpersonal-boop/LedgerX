package com.ledgerx.transfer;

public abstract class TransferException extends RuntimeException {

    protected TransferException(String message) {
        super(message);
    }

    public static class WalletNotFound extends TransferException {
        public WalletNotFound(String message) {
            super(message);
        }
    }

    public static class InsufficientFunds extends TransferException {
        public InsufficientFunds(String message) {
            super(message);
        }
    }

    /** The idempotency key was already used for a different request. */
    public static class IdempotencyConflict extends TransferException {
        public IdempotencyConflict(String message) {
            super(message);
        }
    }

    public static class Invalid extends TransferException {
        public Invalid(String message) {
            super(message);
        }
    }
}
