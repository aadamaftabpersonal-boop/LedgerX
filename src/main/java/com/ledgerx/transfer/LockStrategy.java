package com.ledgerx.transfer;

public enum LockStrategy {
    /** SELECT ... FOR UPDATE on both wallets in id order, then check the balance in Java. */
    PESSIMISTIC,
    /**
     * No explicit lock. The debit is a single conditional UPDATE (balance >= amount) and the two
     * updates run in id order so opposing transfers cannot deadlock.
     */
    CONDITIONAL_UPDATE
}
