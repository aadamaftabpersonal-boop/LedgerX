package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.reconciliation.ReconciliationService;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferException;
import com.ledgerx.transfer.TransferResult;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReversalIT extends AbstractPostgresIT {

    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired ClientRepository clients;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcClient jdbc;

    private UUID payerClient;
    private UUID merchantClient;
    private Wallet payer;
    private Wallet merchant;
    private Transfer payment;

    @BeforeEach
    void setUp() {
        payerClient = clients.create("payer-" + UUID.randomUUID()).client().id();
        merchantClient = clients.create("merchant-" + UUID.randomUUID()).client().id();
        payer = wallets.create(payerClient, "payer", "INR");
        merchant = wallets.create(merchantClient, "merchant", "INR");
        transfers.deposit(payer.id(), 10_000);
        payment = transfers.transfer(payerClient, "pay-" + UUID.randomUUID(), payer.id(), merchant.id(), 2_500)
                .transfer();
    }

    private long balanceOf(Wallet w) {
        return wallets.findById(w.id()).orElseThrow().balance();
    }

    private String newKey() {
        return "rev-" + UUID.randomUUID();
    }

    @Test
    void reversalMovesMoneyBackAndLinksToTheOriginal() {
        TransferResult result = transfers.reverse(merchantClient, newKey(), payment.id());

        Transfer reversal = result.transfer();
        assertThat(result.replayed()).isFalse();
        assertThat(reversal.reversesTransferId()).isEqualTo(payment.id());
        assertThat(reversal.fromWalletId()).isEqualTo(merchant.id());
        assertThat(reversal.toWalletId()).isEqualTo(payer.id());
        assertThat(reversal.amount()).isEqualTo(2_500);
        assertThat(balanceOf(payer)).isEqualTo(10_000);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void theOriginalTransferAndItsLedgerEntriesAreLeftUntouched() {
        transfers.reverse(merchantClient, newKey(), payment.id());

        Integer entries = jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE transfer_id = :t")
                .param("t", payment.id()).query(Integer.class).single();
        assertThat(entries).isEqualTo(2);
        assertThat(transfers.findVisible(payerClient, payment.id()).reversesTransferId()).isNull();
    }

    @Test
    void sameKeyReplaysInsteadOfReversingTwice() {
        String key = newKey();
        TransferResult first = transfers.reverse(merchantClient, key, payment.id());
        TransferResult second = transfers.reverse(merchantClient, key, payment.id());

        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer().id()).isEqualTo(first.transfer().id());
        assertThat(balanceOf(payer)).isEqualTo(10_000);
    }

    @Test
    void aSecondReversalWithAnotherKeyIsRejected() {
        transfers.reverse(merchantClient, newKey(), payment.id());

        assertThatThrownBy(() -> transfers.reverse(merchantClient, newKey(), payment.id()))
                .isInstanceOf(TransferException.AlreadyReversed.class);
        assertThat(balanceOf(payer)).isEqualTo(10_000);
    }

    @Test
    void concurrentReversalsOfTheSameTransferHappenExactlyOnce() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<TransferResult> task = () -> {
                go.await();
                return transfers.reverse(merchantClient, newKey(), payment.id());
            };
            futures.add(pool.submit(task));
        }
        go.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<TransferResult> f : futures) {
            try {
                f.get();
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(TransferException.AlreadyReversed.class);
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(balanceOf(payer)).isEqualTo(10_000);
        assertThat(balanceOf(merchant)).isZero();
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void reversalFailsWhenTheRecipientAlreadySpentTheMoneyAndLeavesNoTrace() {
        Wallet elsewhere = wallets.create(merchantClient, "elsewhere", "INR");
        transfers.transfer(merchantClient, newKey(), merchant.id(), elsewhere.id(), 2_000);
        String key = newKey();

        assertThatThrownBy(() -> transfers.reverse(merchantClient, key, payment.id()))
                .isInstanceOf(TransferException.InsufficientFunds.class);

        assertThat(balanceOf(payer)).isEqualTo(7_500);
        Integer reversals = jdbc.sql("SELECT COUNT(*) FROM transfers WHERE reverses_transfer_id = :t")
                .param("t", payment.id()).query(Integer.class).single();
        Integer keys = jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE client_id = :c AND key = :k")
                .param("c", merchantClient).param("k", key).query(Integer.class).single();
        assertThat(reversals).isZero();
        assertThat(keys).isZero();

        // Once the funds are back, the same key succeeds.
        transfers.transfer(merchantClient, newKey(), elsewhere.id(), merchant.id(), 2_000);
        assertThat(transfers.reverse(merchantClient, key, payment.id()).replayed()).isFalse();
    }

    @Test
    void depositsAndReversalsCannotBeReversed() {
        Transfer deposit = transfers.deposit(merchant.id(), 500);
        TransferResult reversal = transfers.reverse(merchantClient, newKey(), payment.id());

        assertThatThrownBy(() -> transfers.reverse(merchantClient, newKey(), deposit.id()))
                .isInstanceOf(TransferException.Invalid.class).hasMessageContaining("Deposits");
        assertThatThrownBy(() -> transfers.reverse(payerClient, newKey(), reversal.transfer().id()))
                .isInstanceOf(TransferException.Invalid.class).hasMessageContaining("reversal");
    }

    @Test
    void onlyTheRecipientCanReverseAndOtherClientsSeeNothing() {
        UUID stranger = clients.create("stranger-" + UUID.randomUUID()).client().id();

        assertThatThrownBy(() -> transfers.reverse(payerClient, newKey(), payment.id()))
                .isInstanceOf(TransferException.TransferNotFound.class);
        assertThatThrownBy(() -> transfers.reverse(stranger, newKey(), payment.id()))
                .isInstanceOf(TransferException.TransferNotFound.class);
        assertThatThrownBy(() -> transfers.reverse(merchantClient, newKey(), UUID.randomUUID()))
                .isInstanceOf(TransferException.TransferNotFound.class);
        assertThatThrownBy(() -> transfers.findVisible(stranger, payment.id()))
                .isInstanceOf(TransferException.TransferNotFound.class);
        assertThat(transfers.findVisible(payerClient, payment.id()).id()).isEqualTo(payment.id());
        assertThat(transfers.findVisible(merchantClient, payment.id()).id()).isEqualTo(payment.id());
        assertThat(balanceOf(payer)).isEqualTo(7_500);
    }

    @Test
    void reconciliationFlagsAReversalThatDoesNotMirrorItsOriginal() {
        TransferResult reversal = transfers.reverse(merchantClient, newKey(), payment.id());
        jdbc.sql("UPDATE transfers SET amount = amount + 1 WHERE id = :id")
                .param("id", reversal.transfer().id()).update();
        try {
            ReconciliationService.Report report = reconciliation.run();

            assertThat(report.consistent()).isFalse();
            assertThat(report.inconsistentReversals()).containsExactly(reversal.transfer().id());
        } finally {
            jdbc.sql("UPDATE transfers SET amount = amount - 1 WHERE id = :id")
                    .param("id", reversal.transfer().id()).update();
        }
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void theDatabaseItselfRefusesASecondReversalRow() {
        transfers.reverse(merchantClient, newKey(), payment.id());

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount, currency, reverses_transfer_id)
                VALUES (:id, :from, :to, 2500, 'INR', :orig)
                """)
                .param("id", UUID.randomUUID()).param("from", merchant.id()).param("to", payer.id())
                .param("orig", payment.id()).update())
                .hasMessageContaining("uq_transfers_reverses");
    }
}
