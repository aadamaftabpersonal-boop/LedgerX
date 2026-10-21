package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ledgerx.client.ClientRepository;
import com.ledgerx.outbox.EventPublisher;
import com.ledgerx.outbox.OutboxDispatcher;
import com.ledgerx.outbox.OutboxRepository;
import com.ledgerx.outbox.OutboxScheduler;
import com.ledgerx.reconciliation.ReconciliationJob;
import com.ledgerx.reconciliation.ReconciliationService;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The scheduled entry points are disabled in tests, so they are exercised directly here. */
class SchedulersIT extends AbstractPostgresIT {

    @MockitoBean EventPublisher publisher;

    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository outbox;
    @Autowired ReconciliationService reconciliation;
    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired ClientRepository clients;
    @Autowired JdbcClient jdbc;

    @Test
    void outboxSchedulerDrainsEverythingThatIsDue() throws Exception {
        jdbc.sql("DELETE FROM outbox_events").update();
        UUID clientId = clients.create("sched-" + UUID.randomUUID()).client().id();
        Wallet wallet = wallets.create(clientId, "w", "INR");
        for (int i = 0; i < 45; i++) { // more than two batches of 20
            transfers.deposit(wallet.id(), 10);
        }

        new OutboxScheduler(dispatcher).poll();

        verify(publisher, times(45)).publish(any());
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    void reconciliationJobRunsCleanlyOnAHealthyAndOnACorruptedLedger() {
        ReconciliationJob job = new ReconciliationJob(reconciliation);
        assertThatCode(job::run).doesNotThrowAnyException();
        assertThat(reconciliation.run().consistent()).isTrue();

        UUID clientId = clients.create("sched-" + UUID.randomUUID()).client().id();
        Wallet wallet = wallets.create(clientId, "w", "INR");
        transfers.deposit(wallet.id(), 100);
        jdbc.sql("UPDATE wallets SET balance = balance + 1 WHERE id = :id").param("id", wallet.id()).update();
        try {
            assertThatCode(job::run).doesNotThrowAnyException(); // takes the error-logging branch
            assertThat(reconciliation.run().consistent()).isFalse();
        } finally {
            jdbc.sql("UPDATE wallets SET balance = balance - 1 WHERE id = :id").param("id", wallet.id()).update();
        }
        assertThat(reconciliation.run().consistent()).isTrue();
    }
}
