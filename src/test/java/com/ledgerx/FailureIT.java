package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.transfer.TransferResult;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fault injection against a real database. A trigger on ledger_entries fails or stalls the write of
 * one specific wallet's entry, i.e. in the middle of a transfer, after the balances were already updated.
 */
class FailureIT extends AbstractPostgresIT {

    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired ClientRepository clients;
    @Autowired JdbcClient jdbc;

    private UUID clientId;
    private Wallet alice;
    private Wallet bob;

    @BeforeEach
    void setUp() {
        clientId = clients.create("failure-" + UUID.randomUUID()).client().id();
        alice = wallets.create(clientId, "alice", "INR");
        bob = wallets.create(clientId, "bob", "INR");
        transfers.deposit(alice.id(), 1_000);
    }

    @AfterEach
    void removeFault() {
        jdbc.sql("DROP TRIGGER IF EXISTS fault_inject_trg ON ledger_entries").update();
    }

    /** Runs {@code action} (PL/pgSQL) whenever a ledger entry for the given wallet is inserted. */
    private void injectFault(UUID walletId, String action) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fault_inject() RETURNS trigger AS $$
                BEGIN
                    IF NEW.wallet_id = '%s' THEN %s END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """.formatted(walletId, action)).update();
        jdbc.sql("""
                CREATE TRIGGER fault_inject_trg BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION fault_inject()
                """).update();
    }

    private long balanceOf(Wallet w) {
        return wallets.findById(w.id()).orElseThrow().balance();
    }

    private int count(String sql, Object param) {
        return jdbc.sql(sql).param("p", param).query(Integer.class).single();
    }

    private void assertNothingPersisted(String key) {
        assertThat(balanceOf(alice)).as("alice balance").isEqualTo(1_000);
        assertThat(balanceOf(bob)).as("bob balance").isZero();
        assertThat(count("SELECT COUNT(*) FROM transfers WHERE from_wallet_id = :p", alice.id()))
                .as("transfers from alice").isZero();
        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE wallet_id = :p", bob.id()))
                .as("ledger entries for bob").isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE key = :p", key))
                .as("stored idempotency keys").isZero();
    }

    @Test
    void errorAfterBalanceUpdatesRollsBackEverythingAndRetrySucceeds() {
        String key = "fail-" + UUID.randomUUID();
        injectFault(bob.id(), "RAISE EXCEPTION 'injected fault';");

        assertThatThrownBy(() -> transfers.transfer(clientId, key, alice.id(), bob.id(), 400))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("injected fault");
        assertNothingPersisted(key);

        removeFault();
        TransferResult retry = transfers.transfer(clientId, key, alice.id(), bob.id(), 400);

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(alice)).isEqualTo(600);
        assertThat(balanceOf(bob)).isEqualTo(400);
    }

    @Test
    void databaseSessionKilledMidTransferLeavesNoPartialState() throws Exception {
        String key = "kill-" + UUID.randomUUID();
        injectFault(bob.id(), "PERFORM pg_sleep(20);");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TransferResult> inFlight =
                    executor.submit(() -> transfers.transfer(clientId, key, alice.id(), bob.id(), 400));

            // The transfer has updated balances and written alice's entry, and is now stuck on bob's.
            Integer pid = waitForBackendStuckOnLedgerInsert();
            Boolean terminated = jdbc.sql("SELECT pg_terminate_backend(:pid)")
                    .param("pid", pid).query(Boolean.class).single();
            assertThat(terminated).isTrue();

            assertThatThrownBy(() -> inFlight.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        } finally {
            executor.shutdownNow();
        }

        assertNothingPersisted(key);

        removeFault();
        TransferResult retry = transfers.transfer(clientId, key, alice.id(), bob.id(), 400);
        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(alice)).isEqualTo(600);
        assertThat(balanceOf(bob)).isEqualTo(400);
    }

    private Integer waitForBackendStuckOnLedgerInsert() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer pid = jdbc.sql("""
                    SELECT pid FROM pg_stat_activity
                    WHERE pid <> pg_backend_pid() AND state = 'active'
                      AND query LIKE 'INSERT INTO ledger_entries%'
                    """).query(Integer.class).optional().orElse(null);
            if (pid != null) {
                return pid;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Transfer never reached the injected fault");
    }
}
