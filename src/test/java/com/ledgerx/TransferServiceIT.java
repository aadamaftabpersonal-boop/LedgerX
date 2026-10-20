package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferException;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class TransferServiceIT extends AbstractPostgresIT {

    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired JdbcClient jdbc;

    private Wallet fundedWallet(String owner, long amount) {
        Wallet w = wallets.create(null, owner, "INR");
        if (amount > 0) {
            transfers.deposit(w.id(), amount);
        }
        return w;
    }

    private long balanceOf(Wallet w) {
        return wallets.findById(w.id()).orElseThrow().balance();
    }

    @Test
    void transferMovesMoneyAndWritesBalancedLedgerEntries() {
        Wallet alice = fundedWallet("alice", 10_000);
        Wallet bob = fundedWallet("bob", 0);

        Transfer t = transfers.transfer(alice.id(), bob.id(), 2_500);

        assertThat(balanceOf(alice)).isEqualTo(7_500);
        assertThat(balanceOf(bob)).isEqualTo(2_500);

        Long entrySum = jdbc.sql("SELECT SUM(amount) FROM ledger_entries WHERE transfer_id = :t")
                .param("t", t.id()).query(Long.class).single();
        Integer entryCount = jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = :t")
                .param("t", t.id()).query(Integer.class).single();
        assertThat(entrySum).isZero();
        assertThat(entryCount).isEqualTo(2);
    }

    @Test
    void overdraftIsRejectedAndLeavesNoTrace() {
        Wallet alice = fundedWallet("alice", 1_000);
        Wallet bob = fundedWallet("bob", 0);

        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), 1_001))
                .isInstanceOf(TransferException.InsufficientFunds.class);

        assertThat(balanceOf(alice)).isEqualTo(1_000);
        assertThat(balanceOf(bob)).isZero();
    }

    @Test
    void rejectsInvalidTransfers() {
        Wallet alice = fundedWallet("alice", 1_000);
        Wallet usd = wallets.create(null, "carol", "USD");

        assertThatThrownBy(() -> transfers.transfer(alice.id(), alice.id(), 100))
                .isInstanceOf(TransferException.Invalid.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), usd.id(), 100))
                .isInstanceOf(TransferException.Invalid.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), wallets.create(null, "bob", "INR").id(), 0))
                .isInstanceOf(TransferException.Invalid.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), java.util.UUID.randomUUID(), 100))
                .isInstanceOf(TransferException.WalletNotFound.class);
    }

    @Test
    void externalWalletsCannotBeUsedInUserTransfers() {
        Wallet alice = fundedWallet("alice", 1_000);
        var externalInr = wallets.externalWalletId("INR");

        assertThatThrownBy(() -> transfers.transfer(externalInr, alice.id(), 100))
                .isInstanceOf(TransferException.Invalid.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), externalInr, 100))
                .isInstanceOf(TransferException.Invalid.class);
    }

    @Test
    void ledgerIsAppendOnly() {
        Wallet alice = fundedWallet("alice", 1_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE wallet_id = :w")
                .param("w", alice.id()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE wallet_id = :w")
                .param("w", alice.id()).update()).hasMessageContaining("append-only");
    }

    @Test
    void walletBalancesAlwaysEqualTheirLedgerSum() {
        Wallet alice = fundedWallet("alice", 5_000);
        Wallet bob = fundedWallet("bob", 5_000);
        transfers.transfer(alice.id(), bob.id(), 1_234);
        transfers.transfer(bob.id(), alice.id(), 99);

        Integer mismatches = jdbc.sql("""
                SELECT COUNT(*) FROM wallets w
                WHERE w.balance <> COALESCE((SELECT SUM(amount) FROM ledger_entries l WHERE l.wallet_id = w.id), 0)
                """).query(Integer.class).single();
        Long total = jdbc.sql("SELECT SUM(balance) FROM wallets").query(Long.class).single();

        assertThat(mismatches).isZero();
        assertThat(total).isZero(); // money is only ever moved, never created
    }
}
