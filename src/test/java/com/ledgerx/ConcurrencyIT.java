package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.transfer.LockStrategy;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ConcurrencyIT extends AbstractPostgresIT {

    private static final int THREADS = 16;

    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired JdbcClient jdbc;
    @Autowired ClientRepository clients;

    private UUID clientId;

    @BeforeEach
    void createClient() {
        clientId = clients.create("test-" + UUID.randomUUID()).client().id();
    }

    @AfterEach
    void restoreDefaultStrategy() {
        transfers.setLockStrategy(LockStrategy.PESSIMISTIC);
    }

    private Wallet fundedWallet(String owner, long amount) {
        Wallet w = wallets.create(clientId, owner, "INR");
        if (amount > 0) {
            transfers.deposit(w.id(), amount);
        }
        return w;
    }

    private long balanceOf(Wallet w) {
        return wallets.findById(w.id()).orElseThrow().balance();
    }

    /** Runs all tasks at the same instant on separate threads and returns every outcome. */
    private <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // Only the first THREADS tasks can be running at once, so only wait for that many at the gate.
        CountDownLatch ready = new CountDownLatch(Math.min(tasks.size(), THREADS));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(); // returns immediately for tasks that start after the gate opened
                return task.call();
            }));
        }
        ready.await();
        go.countDown();

        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                outcomes.add(new Outcome<>(f.get(), null));
            } catch (ExecutionException e) {
                outcomes.add(new Outcome<>(null, e.getCause()));
            }
        }
        pool.shutdown();
        return outcomes;
    }

    private record Outcome<T>(T value, Throwable error) {}

    private void assertLedgerConsistent() {
        Integer mismatches = jdbc.sql("""
                SELECT COUNT(*) FROM wallets w
                WHERE w.balance <> COALESCE((SELECT SUM(amount) FROM ledger_entries l WHERE l.wallet_id = w.id), 0)
                """).query(Integer.class).single();
        Integer unbalanced = jdbc.sql("""
                SELECT COUNT(*) FROM (SELECT transfer_id FROM ledger_entries
                GROUP BY transfer_id HAVING SUM(amount) <> 0) x
                """).query(Integer.class).single();
        Long total = jdbc.sql("SELECT SUM(balance) FROM wallets").query(Long.class).single();
        Integer negative = jdbc.sql("SELECT COUNT(*) FROM wallets WHERE kind = 'USER' AND balance < 0")
                .query(Integer.class).single();
        assertThat(mismatches).as("wallets whose balance differs from their ledger sum").isZero();
        assertThat(unbalanced).as("transfers whose ledger entries do not sum to zero").isZero();
        assertThat(total).as("sum of all balances").isZero();
        assertThat(negative).as("overdrawn user wallets").isZero();
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void concurrentTransfersNeverOverspend(LockStrategy strategy) throws Exception {
        transfers.setLockStrategy(strategy);
        Wallet source = fundedWallet("source", 1_000);
        Wallet sink = fundedWallet("sink", 0);

        // 100 requests of 100 each against a balance of 1000: exactly 10 may succeed.
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> transfers.transfer(source.id(), sink.id(), 100));
        }
        List<Outcome<Transfer>> outcomes = runConcurrently(tasks);

        long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
        long rejected = outcomes.stream()
                .filter(o -> o.error() instanceof TransferException.InsufficientFunds).count();
        assertThat(succeeded).isEqualTo(10);
        assertThat(rejected).isEqualTo(90);
        assertThat(balanceOf(source)).isZero();
        assertThat(balanceOf(sink)).isEqualTo(1_000);
        assertLedgerConsistent();
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void opposingTransfersDoNotDeadlock(LockStrategy strategy) throws Exception {
        transfers.setLockStrategy(strategy);
        Wallet a = fundedWallet("a", 100_000);
        Wallet b = fundedWallet("b", 100_000);

        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> forward
                    ? transfers.transfer(a.id(), b.id(), 10)
                    : transfers.transfer(b.id(), a.id(), 10));
        }
        List<Outcome<Transfer>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceOf(a)).isEqualTo(100_000);
        assertThat(balanceOf(b)).isEqualTo(100_000);
        assertLedgerConsistent();
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void concurrentDuplicateRequestsExecuteOnce(LockStrategy strategy) throws Exception {
        transfers.setLockStrategy(strategy);
        Wallet alice = fundedWallet("alice", 10_000);
        Wallet bob = fundedWallet("bob", 0);
        String key = "dup-" + UUID.randomUUID();

        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> transfers.transfer(clientId, key, alice.id(), bob.id(), 700));
        }
        List<Outcome<TransferResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().map(o -> o.value().transfer().id()).distinct().count()).isEqualTo(1);
        assertThat(balanceOf(alice)).isEqualTo(9_300);
        assertThat(balanceOf(bob)).isEqualTo(700);
        assertLedgerConsistent();
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void keyReusedWithDifferentRequestIsRejected(LockStrategy strategy) {
        transfers.setLockStrategy(strategy);
        Wallet alice = fundedWallet("alice", 10_000);
        Wallet bob = fundedWallet("bob", 0);
        String key = "reuse-" + UUID.randomUUID();

        transfers.transfer(clientId, key, alice.id(), bob.id(), 100);

        assertThatThrownBy(() -> transfers.transfer(clientId, key, alice.id(), bob.id(), 200))
                .isInstanceOf(TransferException.IdempotencyConflict.class);
        assertThat(balanceOf(alice)).isEqualTo(9_900);
    }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void failedTransferDoesNotConsumeItsIdempotencyKey(LockStrategy strategy) {
        transfers.setLockStrategy(strategy);
        Wallet alice = fundedWallet("alice", 50);
        Wallet bob = fundedWallet("bob", 0);
        String key = "retry-" + UUID.randomUUID();

        assertThatThrownBy(() -> transfers.transfer(clientId, key, alice.id(), bob.id(), 100))
                .isInstanceOf(TransferException.InsufficientFunds.class);

        transfers.deposit(alice.id(), 100);
        TransferResult retry = transfers.transfer(clientId, key, alice.id(), bob.id(), 100);

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(bob)).isEqualTo(100);
        assertLedgerConsistent();
    }
}
