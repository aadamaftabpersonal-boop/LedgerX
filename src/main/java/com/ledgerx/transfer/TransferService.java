package com.ledgerx.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.outbox.OutboxRepository;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final String TRANSFER_COLUMNS =
            "id, from_wallet_id, to_wallet_id, amount, currency, reverses_transfer_id, created_at";

    private final WalletRepository wallets;
    private final JdbcClient jdbc;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private volatile LockStrategy lockStrategy;

    public TransferService(
            WalletRepository wallets,
            JdbcClient jdbc,
            OutboxRepository outbox,
            ObjectMapper objectMapper,
            @Value("${ledgerx.transfer.lock-strategy:PESSIMISTIC}") LockStrategy lockStrategy) {
        this.wallets = wallets;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.lockStrategy = lockStrategy;
    }

    public LockStrategy lockStrategy() {
        return lockStrategy;
    }

    /** Switches strategy at runtime so tests and benchmarks can compare them. */
    public void setLockStrategy(LockStrategy lockStrategy) {
        this.lockStrategy = lockStrategy;
    }

    /** Idempotent user-to-user transfer. */
    @Transactional
    public TransferResult transfer(UUID clientId, String idempotencyKey, UUID fromId, UUID toId, long amount) {
        return idempotent(clientId, idempotencyKey, requestHash(fromId, toId, amount),
                () -> execute(fromId, toId, amount, false, clientId, null));
    }

    /**
     * Idempotent reversal (refund) of a transfer. Only the owner of the original recipient wallet may reverse it,
     * and only once. The money moves back through a compensating transfer; nothing in the ledger is edited.
     */
    @Transactional
    public TransferResult reverse(UUID clientId, String idempotencyKey, UUID transferId) {
        return idempotent(clientId, idempotencyKey, requestHash("reverse", transferId, 0),
                () -> doReverse(clientId, transferId));
    }

    /** Returns a transfer only to a client that owns its source or destination wallet. */
    @Transactional(readOnly = true)
    public Transfer findVisible(UUID clientId, UUID transferId) {
        Transfer transfer = findTransferOrNull(transferId);
        if (transfer == null || !isParty(clientId, transfer)) {
            throw new TransferException.TransferNotFound("Transfer not found: " + transferId);
        }
        return transfer;
    }

    /** Non-idempotent, unauthenticated transfer for internal use and tests. EXTERNAL wallets cannot be used through this path. */
    @Transactional
    public Transfer transfer(UUID fromId, UUID toId, long amount) {
        return execute(fromId, toId, amount, false, null, null);
    }

    /** Funds a wallet from the EXTERNAL counterparty of its currency. */
    @Transactional
    public Transfer deposit(UUID walletId, long amount) {
        Wallet target = wallets.findById(walletId)
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + walletId));
        UUID external = wallets.externalWalletId(target.currency());
        return execute(external, walletId, amount, true, null, null);
    }

    /**
     * The key row and the guarded action commit together: a concurrent duplicate blocks on the key's unique
     * index until the first request finishes, then replays its result.
     */
    private TransferResult idempotent(UUID clientId, String key, String hash, Supplier<Transfer> action) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (client_id, key, request_hash) VALUES (:client, :key, :hash)
                ON CONFLICT (client_id, key) DO NOTHING
                """)
                .param("client", clientId)
                .param("key", key)
                .param("hash", hash)
                .update();

        if (inserted == 0) {
            String storedHash = jdbc.sql("SELECT request_hash FROM idempotency_keys WHERE client_id = :client AND key = :key")
                    .param("client", clientId)
                    .param("key", key)
                    .query(String.class)
                    .single();
            if (!storedHash.equals(hash)) {
                throw new TransferException.IdempotencyConflict(
                        "Idempotency key was already used with a different request");
            }
            UUID storedTransferId = jdbc.sql("SELECT transfer_id FROM idempotency_keys WHERE client_id = :client AND key = :key")
                    .param("client", clientId)
                    .param("key", key)
                    .query(UUID.class)
                    .single();
            return new TransferResult(findTransfer(storedTransferId), true);
        }

        Transfer transfer = action.get();
        jdbc.sql("UPDATE idempotency_keys SET transfer_id = :t WHERE client_id = :client AND key = :key")
                .param("t", transfer.id())
                .param("client", clientId)
                .param("key", key)
                .update();
        return new TransferResult(transfer, false);
    }

    private Transfer doReverse(UUID clientId, UUID transferId) {
        Transfer original = findTransferOrNull(transferId);
        Wallet recipient = original == null ? null : wallets.findById(original.toWalletId()).orElse(null);
        // Unknown transfers and transfers the caller did not receive look identical (404).
        if (original == null || recipient == null || !clientId.equals(recipient.clientId())) {
            throw new TransferException.TransferNotFound("Transfer not found: " + transferId);
        }
        Wallet sender = wallets.findById(original.fromWalletId())
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + original.fromWalletId()));
        if (sender.isExternal()) {
            throw new TransferException.Invalid("Deposits cannot be reversed");
        }
        if (original.reversesTransferId() != null) {
            throw new TransferException.Invalid("A reversal cannot be reversed");
        }

        // Serialises concurrent reversals of the same transfer. The unique index is the backstop.
        jdbc.sql("SELECT id FROM transfers WHERE id = :id FOR UPDATE")
                .param("id", transferId).query(UUID.class).single();
        boolean alreadyReversed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM transfers WHERE reverses_transfer_id = :id)")
                .param("id", transferId).query(Boolean.class).single();
        if (alreadyReversed) {
            throw new TransferException.AlreadyReversed("Transfer " + transferId + " was already reversed");
        }

        return execute(original.toWalletId(), original.fromWalletId(), original.amount(), false, clientId, transferId);
    }

    /** {@code actingClient} (when set) must own the source wallet; otherwise the wallet looks nonexistent. */
    private Transfer execute(UUID fromId, UUID toId, long amount, boolean allowExternalSource,
                             UUID actingClient, UUID reversesTransferId) {
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
                INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount, currency, reverses_transfer_id)
                VALUES (:id, :from, :to, :amount, :currency, :reverses)
                """)
                .param("id", transferId)
                .param("from", fromId)
                .param("to", toId)
                .param("amount", amount)
                .param("currency", from.currency())
                .param("reverses", reversesTransferId, Types.OTHER)
                .update();
        insertEntry(transferId, fromId, -amount);
        insertEntry(transferId, toId, amount);

        Transfer created = findTransfer(transferId);
        // Same transaction as the money movement: the event exists if and only if the transfer committed.
        outbox.insert(reversesTransferId == null ? "transfer.completed" : "transfer.reversed", transferId,
                eventPayload(created));
        return created;
    }

    private String eventPayload(Transfer transfer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transfer.id());
        body.put("fromWalletId", transfer.fromWalletId());
        body.put("toWalletId", transfer.toWalletId());
        body.put("amount", transfer.amount());
        body.put("currency", transfer.currency());
        body.put("reversesTransferId", transfer.reversesTransferId());
        body.put("occurredAt", transfer.createdAt().toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise transfer event", e);
        }
    }

    private void debit(Wallet from, long amount) {
        if (from.isExternal()) {
            wallets.applyDelta(from.id(), -amount);
        } else if (!wallets.debitIfSufficient(from.id(), amount)) {
            throw new TransferException.InsufficientFunds("Insufficient funds in wallet " + from.id());
        }
    }

    private boolean isParty(UUID clientId, Transfer transfer) {
        return wallets.findOwned(transfer.fromWalletId(), clientId).isPresent()
                || wallets.findOwned(transfer.toWalletId(), clientId).isPresent();
    }

    private Wallet find(List<Wallet> rows, UUID id) {
        return rows.stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + id));
    }

    private Transfer findTransfer(UUID id) {
        return jdbc.sql("SELECT " + TRANSFER_COLUMNS + " FROM transfers WHERE id = :id")
                .param("id", id)
                .query(Transfer.class)
                .single();
    }

    private Transfer findTransferOrNull(UUID id) {
        return jdbc.sql("SELECT " + TRANSFER_COLUMNS + " FROM transfers WHERE id = :id")
                .param("id", id)
                .query(Transfer.class)
                .optional()
                .orElse(null);
    }

    private void insertEntry(UUID transferId, UUID walletId, long amount) {
        jdbc.sql("INSERT INTO ledger_entries (transfer_id, wallet_id, amount) VALUES (:t, :w, :a)")
                .param("t", transferId)
                .param("w", walletId)
                .param("a", amount)
                .update();
    }

    private static String requestHash(Object a, Object b, long amount) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((a + "|" + b + "|" + amount).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
