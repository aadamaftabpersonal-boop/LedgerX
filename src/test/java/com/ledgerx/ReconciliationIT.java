package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.reconciliation.ReconciliationService;
import com.ledgerx.reconciliation.ReconciliationService.Report;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Corrupts the database on purpose and checks that the audit notices, then repairs it. */
@AutoConfigureMockMvc
class ReconciliationIT extends AbstractPostgresIT {

    @Autowired ReconciliationService reconciliation;
    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired ClientRepository clients;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private Wallet alice;
    private Transfer deposit;

    @BeforeEach
    void setUp() {
        UUID clientId = clients.create("recon-" + UUID.randomUUID()).client().id();
        alice = wallets.create(clientId, "alice", "INR");
        deposit = transfers.deposit(alice.id(), 1_000);
    }

    @Test
    void healthySystemIsConsistent() {
        Report report = reconciliation.run();

        assertThat(report.consistent()).isTrue();
        assertThat(report.discrepancies()).isZero();
        assertThat(report.totalBalance()).isZero();
    }

    @Test
    void detectsBalanceThatNoLongerMatchesTheLedger() {
        jdbc.sql("UPDATE wallets SET balance = balance + 5 WHERE id = :id").param("id", alice.id()).update();
        try {
            Report report = reconciliation.run();

            assertThat(report.consistent()).isFalse();
            assertThat(report.balanceMismatches()).singleElement().satisfies(m -> {
                assertThat(m.walletId()).isEqualTo(alice.id());
                assertThat(m.balance()).isEqualTo(1_005);
                assertThat(m.ledgerSum()).isEqualTo(1_000);
            });
            assertThat(report.totalBalance()).isEqualTo(5);
        } finally {
            jdbc.sql("UPDATE wallets SET balance = balance - 5 WHERE id = :id").param("id", alice.id()).update();
        }
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void detectsStrayLedgerEntry() {
        Long strayId = jdbc.sql("""
                INSERT INTO ledger_entries (transfer_id, wallet_id, amount) VALUES (:t, :w, 7) RETURNING id
                """).param("t", deposit.id()).param("w", alice.id()).query(Long.class).single();
        try {
            Report report = reconciliation.run();

            assertThat(report.consistent()).isFalse();
            assertThat(report.unbalancedTransfers()).contains(deposit.id());
            assertThat(report.malformedTransfers()).contains(deposit.id());
            assertThat(report.balanceMismatches()).extracting(m -> m.walletId()).contains(alice.id());
        } finally {
            // The ledger is append-only, so repairing the test data means briefly lifting that guard.
            jdbc.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_no_update").update();
            try {
                jdbc.sql("DELETE FROM ledger_entries WHERE id = :id").param("id", strayId).update();
            } finally {
                jdbc.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_no_update").update();
            }
        }
        assertThat(reconciliation.run().consistent()).isTrue();
    }

    @Test
    void adminEndpointReturnsReportAndRequiresAdminKey() throws Exception {
        mvc.perform(get("/admin/reconciliation").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.discrepancies").value(0));
        mvc.perform(get("/admin/reconciliation").header("X-Admin-Key", "wrong")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/reconciliation")).andExpect(status().isForbidden());
    }
}
