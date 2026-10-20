package com.ledgerx.wallet;

public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String currency) {
        super("Unsupported currency: " + currency);
    }
}
