package com.ledgerx.transfer;

import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final WalletRepository wallets;
    private final JdbcClient jdbc;

    public TransferService(WalletRepository wallets, JdbcClient jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    /** User-to-user transfer. EXTERNAL wallets cannot be used through this path. */
    @Transactional
    public Transfer transfer(UUID fromId, UUID toId, long amount) {
        return execute(fromId, toId, amount, false);
    }

    /** Funds a wallet from the EXTERNAL counterparty of its currency. */
    @Transactional
    public Transfer deposit(UUID walletId, long amount) {
        Wallet target = wallets.findById(walletId)
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + walletId));
        UUID external = wallets.externalWalletId(target.currency());
        return execute(external, walletId, amount, true);
    }

    private Transfer execute(UUID fromId, UUID toId, long amount, boolean allowExternalSource) {
        if (amount <= 0) {
            throw new TransferException.Invalid("Amount must be positive");
        }
        if (fromId.equals(toId)) {
            throw new TransferException.Invalid("Cannot transfer to the same wallet");
        }

        List<Wallet> locked = wallets.lockInOrder(fromId, toId);
        Wallet from = locked.stream().filter(w -> w.id().equals(fromId)).findFirst()
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + fromId));
        Wallet to = locked.stream().filter(w -> w.id().equals(toId)).findFirst()
                .orElseThrow(() -> new TransferException.WalletNotFound("Wallet not found: " + toId));

        if (!from.currency().equals(to.currency())) {
            throw new TransferException.Invalid("Currency mismatch");
        }
        if (to.isExternal() || (from.isExternal() && !allowExternalSource)) {
            throw new TransferException.Invalid("External wallets cannot be used in transfers");
        }
        if (!from.isExternal() && from.balance() < amount) {
            throw new TransferException.InsufficientFunds("Insufficient funds in wallet " + fromId);
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
        wallets.applyDelta(fromId, -amount);
        wallets.applyDelta(toId, amount);

        return jdbc.sql("""
                SELECT id, from_wallet_id, to_wallet_id, amount, currency, created_at
                FROM transfers WHERE id = :id
                """)
                .param("id", transferId)
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
}
