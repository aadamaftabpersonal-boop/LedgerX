package com.ledgerx.transfer;

import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final WalletRepository wallets;
    private final JdbcClient jdbc;
    private volatile LockStrategy lockStrategy;

    public TransferService(
            WalletRepository wallets,
            JdbcClient jdbc,
            @Value("${ledgerx.transfer.lock-strategy:PESSIMISTIC}") LockStrategy lockStrategy) {
        this.wallets = wallets;
        this.jdbc = jdbc;
        this.lockStrategy = lockStrategy;
    }

    public LockStrategy lockStrategy() {
        return lockStrategy;
    }

    /** Switches strategy at runtime so tests and benchmarks can compare them. */
    public void setLockStrategy(LockStrategy lockStrategy) {
        this.lockStrategy = lockStrategy;
    }

    /**
     * Idempotent user-to-user transfer. The key row and the transfer commit together: a concurrent
     * duplicate blocks on the key's unique index until the first request finishes, then replays its result.
     */
    @Transactional
    public TransferResult transfer(UUID clientId, String idempotencyKey, UUID fromId, UUID toId, long amount) {
        String hash = requestHash(fromId, toId, amount);
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (client_id, key, request_hash) VALUES (:client, :key, :hash)
                ON CONFLICT (client_id, key) DO NOTHING
                """)
                .param("client", clientId)
                .param("key", idempotencyKey)
                .param("hash", hash)
                .update();

        if (inserted == 0) {
            String storedHash = jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE client_id = :client AND key = :key")
                    .param("client", clientId)
                    .param("key", idempotencyKey)
                    .query(String.class)
                    .single();
            if (!storedHash.equals(hash)) {
                throw new TransferException.IdempotencyConflict(
                        "Idempotency key was already used with a different request");
            }
            UUID storedTransferId = jdbc.sql("SELECT transfer_id FROM idempotency_keys WHERE client_id = :client AND key = :key")
                    .param("client", clientId)
                    .param("key", idempotencyKey)
                    .query(UUID.class)
                    .single();
            return new TransferResult(findTransfer(storedTransferId), true);
        }

        Transfer transfer = execute(fromId, toId, amount, false, clientId);
        jdbc.sql("UPDATE idempotency_keys SET transfer_id = :t WHERE client_id = :client AND key = :key")
                .param("t", transfer.id())
                .param("client", clientId)
                .param("key", idempotencyKey)
                .update();
        return new TransferResult(transfer, false);
    }

    /** Non-idempotent, unauthenticated transfer for internal use and tests. EXTERNAL wallets cannot be used through this path. */
    @Transactional
    public Transfer transfer(UUID fromId, UUID toId, long amount) {
        return execute(fromId, toId, amount, false, null);
    }

    /** Funds a wallet from the EXTERNAL counterparty of its currency. */
    @Transactional
    public Transfer deposit(UUID walletId, long amount) {
        Wallet target = wallets.findById(walletId)
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + walletId));
        UUID external = wallets.externalWalletId(target.currency());
        return execute(external, walletId, amount, true, null);
    }

    /** {@code actingClient} (when set) must own the source wallet; otherwise the wallet looks nonexistent. */
    private Transfer execute(UUID fromId, UUID toId, long amount, boolean allowExternalSource, UUID actingClient) {
        if (amount <= 0) {
            throw new TransferException.Invalid("Amount must be positive");
        }
        if (fromId.equals(toId)) {
            throw new TransferException.Invalid("Cannot transfer to the same wallet");
        }

        LockStrategy strategy = lockStrategy;
        // PESSIMISTIC locks the rows here. CONDITIONAL_UPDATE only reads them: currency and kind never change.
        List<Wallet> rows = strategy == LockStrategy.PESSIMISTIC
                ? wallets.lockInOrder(fromId, toId)
                : wallets.readPair(fromId, toId);
        Wallet from = find(rows, fromId);
        Wallet to = find(rows, toId);

        if (actingClient != null && !actingClient.equals(from.clientId())) {
            throw new TransferException.WalletNotFound("Wallet not found: " + fromId);
        }

        if (!from.currency().equals(to.currency())) {
            throw new TransferException.Invalid("Currency mismatch");
        }
        if (to.isExternal() || (from.isExternal() && !allowExternalSource)) {
            throw new TransferException.Invalid("External wallets cannot be used in transfers");
        }

        if (strategy == LockStrategy.PESSIMISTIC) {
            if (!from.isExternal() && from.balance() < amount) {
                throw new TransferException.InsufficientFunds("Insufficient funds in wallet " + fromId);
            }
            wallets.applyDelta(fromId, -amount);
            wallets.applyDelta(toId, amount);
        } else {
            // Always touch the lower id first so two opposing transfers acquire row locks in the same order.
            if (fromId.compareTo(toId) < 0) {
                debit(from, amount);
                wallets.applyDelta(toId, amount);
            } else {
                wallets.applyDelta(toId, amount);
                debit(from, amount);
            }
        }

        UUID transferId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount, currency)
                VALUES (:id, :from, :to, :amount, :currency)
                """)
                .param("id", transferId)
                .param("from", fromId)
                .param("to", toId)
                .param("amount", amount)
                .param("currency", from.currency())
                .update();
        insertEntry(transferId, fromId, -amount);
        insertEntry(transferId, toId, amount);

        return findTransfer(transferId);
    }

    private void debit(Wallet from, long amount) {
        if (from.isExternal()) {
            wallets.applyDelta(from.id(), -amount);
        } else if (!wallets.debitIfSufficient(from.id(), amount)) {
            throw new TransferException.InsufficientFunds("Insufficient funds in wallet " + from.id());
        }
    }

    private Wallet find(List<Wallet> rows, UUID id) {
        return rows.stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + id));
    }

    private Transfer findTransfer(UUID id) {
        return jdbc.sql("""
                SELECT id, from_wallet_id, to_wallet_id, amount, currency, created_at
                FROM transfers WHERE id = :id
                """)
                .param("id", id)
                .query(Transfer.class)
                .single();
    }

    private void insertEntry(UUID transferId, UUID walletId, long amount) {
        jdbc.sql("INSERT INTO ledger_entries (transfer_id, wallet_id, amount) VALUES (:t, :w, :a)")
                .param("t", transferId)
                .param("w", walletId)
                .param("a", amount)
                .update();
    }

    private static String requestHash(UUID from, UUID to, long amount) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((from + "|" + to + "|" + amount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
