package com.ledgerx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.client.ClientRepository;
import com.ledgerx.outbox.EventPublisher;
import com.ledgerx.outbox.OutboxDispatcher;
import com.ledgerx.outbox.OutboxEvent;
import com.ledgerx.outbox.OutboxRepository;
import com.ledgerx.transfer.Transfer;
import com.ledgerx.transfer.TransferException;
import com.ledgerx.transfer.TransferService;
import com.ledgerx.wallet.Wallet;
import com.ledgerx.wallet.WalletRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "ledgerx.outbox.max-attempts=3")
class OutboxIT extends AbstractPostgresIT {

    @MockitoBean EventPublisher publisher;

    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository outbox;
    @Autowired TransferService transfers;
    @Autowired WalletRepository wallets;
    @Autowired ClientRepository clients;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    private UUID clientId;
    private Wallet alice;
    private Wallet bob;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM outbox_events").update();
        clientId = clients.create("outbox-" + UUID.randomUUID()).client().id();
        alice = wallets.create(clientId, "alice", "INR");
        bob = wallets.create(clientId, "bob", "INR");
    }

    private int count(String where) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events " + where).query(Integer.class).single();
    }

    @Test
    void everyMoneyMovementWritesExactlyOneMatchingEvent() throws Exception {
        Transfer deposit = transfers.deposit(alice.id(), 1_000);
        Transfer payment = transfers.transfer(clientId, "p-" + UUID.randomUUID(), alice.id(), bob.id(), 400).transfer();
        Transfer refund = transfers.reverse(clientId, "r-" + UUID.randomUUID(), payment.id()).transfer();

        List<Map<String, Object>> rows = jdbc.sql("SELECT event_type, aggregate_id, payload::text AS payload FROM outbox_events ORDER BY id")
                .query().listOfRows();
        assertThat(rows).extracting(r -> r.get("event_type"))
                .containsExactly("transfer.completed", "transfer.completed", "transfer.reversed");
        assertThat(rows).extracting(r -> r.get("aggregate_id").toString())
                .containsExactly(deposit.id().toString(), payment.id().toString(), refund.id().toString());

        JsonNode reversal = json.readTree(rows.get(2).get("payload").toString());
        assertThat(reversal.get("transferId").asText()).isEqualTo(refund.id().toString());
        assertThat(reversal.get("reversesTransferId").asText()).isEqualTo(payment.id().toString());
        assertThat(reversal.get("amount").asLong()).isEqualTo(400);
        assertThat(reversal.get("currency").asText()).isEqualTo("INR");
    }

    @Test
    void failedAndReplayedTransfersWriteNoEvent() {
        transfers.deposit(alice.id(), 100);
        assertThat(count("")).isEqualTo(1);

        assertThatThrownBy(() -> transfers.transfer(clientId, "too-much", alice.id(), bob.id(), 500))
                .isInstanceOf(TransferException.InsufficientFunds.class);
        assertThat(count("")).isEqualTo(1);

        transfers.transfer(clientId, "same-key", alice.id(), bob.id(), 40);
        transfers.transfer(clientId, "same-key", alice.id(), bob.id(), 40); // replay
        assertThat(count("")).isEqualTo(2);
    }

    @Test
    void dispatchDeliversEventsInOrderExactlyOnce() throws Exception {
        transfers.deposit(alice.id(), 1_000);
        for (int i = 0; i < 4; i++) {
            transfers.transfer(clientId, "k" + i + UUID.randomUUID(), alice.id(), bob.id(), 10);
        }

        int attempted = 0;
        int n;
        while ((n = dispatcher.dispatchBatch()) > 0) {
            attempted += n;
        }

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(publisher, times(5)).publish(captor.capture());
        assertThat(attempted).isEqualTo(5);
        assertThat(captor.getAllValues()).extracting(OutboxEvent::id).isSorted().doesNotHaveDuplicates();
        assertThat(count("WHERE delivered_at IS NOT NULL")).isEqualTo(5);
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    void failedDeliveryIsRetriedLaterWithBackoff() throws Exception {
        transfers.deposit(alice.id(), 100);
        doThrow(new IOException("consumer down")).doNothing().when(publisher).publish(any());

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count("WHERE attempts = 1 AND delivered_at IS NULL AND next_attempt_at > now()")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_events").query(String.class).single()).contains("consumer down");

        assertThat(dispatcher.dispatchBatch()).as("not due yet, so not retried immediately").isZero();

        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'").update();
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count("WHERE delivered_at IS NOT NULL AND attempts = 2 AND last_error IS NULL")).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesMakeTheEventDeadAndRequeueRevivesIt() throws Exception {
        transfers.deposit(alice.id(), 100);
        doThrow(new IOException("still down")).when(publisher).publish(any());

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
            jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'").update();
        }

        assertThat(count("WHERE dead AND attempts = 3")).isEqualTo(1);
        assertThat(outbox.deadCount()).isEqualTo(1);
        assertThat(outbox.pendingCount()).isZero();
        assertThat(dispatcher.dispatchBatch()).as("dead events are never retried").isZero();

        doNothing().when(publisher).publish(any());
        assertThat(outbox.requeueDead()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(count("WHERE delivered_at IS NOT NULL AND NOT dead")).isEqualTo(1);
    }

    @Test
    void concurrentDispatchersNeverDeliverTheSameEventTwice() throws Exception {
        transfers.deposit(alice.id(), 100_000);
        for (int i = 0; i < 60; i++) {
            transfers.transfer(clientId, "c" + i + UUID.randomUUID(), alice.id(), bob.id(), 1);
        }
        Map<Long, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            deliveries.computeIfAbsent(event.id(), id -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(3); // make the dispatchers overlap
            return null;
        }).when(publisher).publish(any());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workers.add(pool.submit(() -> {
                while (dispatcher.dispatchBatch() > 0) {
                    // keep draining
                }
            }));
        }
        for (Future<?> worker : workers) {
            worker.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(deliveries).hasSize(61);
        assertThat(deliveries.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(count("WHERE delivered_at IS NULL")).isZero();
    }
}
