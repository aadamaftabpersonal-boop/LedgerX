package com.ledgerx.wallet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private static final String COLUMNS = "id, owner, currency, kind, balance, version, created_at";

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Wallet create(String owner, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO wallets (id, owner, currency) VALUES (:id, :owner, :currency)")
                .param("id", id)
                .param("owner", owner)
                .param("currency", currency)
                .update();
        return findById(id).orElseThrow();
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM wallets WHERE id = :id")
                .param("id", id)
                .query(Wallet.class)
                .optional();
    }

    /**
     * Locks the given wallets with SELECT ... FOR UPDATE, always in ascending id order so that two
     * transfers touching the same pair of wallets cannot deadlock. Must run inside a transaction.
     */
    public List<Wallet> lockInOrder(UUID a, UUID b) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM wallets WHERE id IN (:a, :b) ORDER BY id FOR UPDATE")
                .param("a", a)
                .param("b", b)
                .query(Wallet.class)
                .list();
    }

    /** Plain read of both wallets, no row locks. */
    public List<Wallet> readPair(UUID a, UUID b) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM wallets WHERE id IN (:a, :b) ORDER BY id")
                .param("a", a)
                .param("b", b)
                .query(Wallet.class)
                .list();
    }

    /** Atomic check-and-debit: returns false (and changes nothing) when the balance is too low. */
    public boolean debitIfSufficient(UUID id, long amount) {
        return jdbc.sql("""
                UPDATE wallets SET balance = balance - :amount, version = version + 1
                WHERE id = :id AND balance >= :amount
                """)
                .param("amount", amount)
                .param("id", id)
                .update() == 1;
    }

    public void applyDelta(UUID id, long delta) {
        jdbc.sql("UPDATE wallets SET balance = balance + :delta, version = version + 1 WHERE id = :id")
                .param("delta", delta)
                .param("id", id)
                .update();
    }

    public UUID externalWalletId(String currency) {
        return jdbc.sql("SELECT id FROM wallets WHERE kind = 'EXTERNAL' AND currency = :currency")
                .param("currency", currency)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new UnsupportedCurrencyException(currency));
    }
}
