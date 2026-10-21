package com.ledgerx.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audits the ledger against the cached wallet balances. The ledger is the source of truth; any
 * difference means a bug, manual tampering, or a partial failure that slipped past the transaction.
 */
@Service
public class ReconciliationService {

    /** A wallet whose stored balance differs from the sum of its ledger entries. */
    public record BalanceMismatch(UUID walletId, long balance, long ledgerSum) {}

    public record Report(
            OffsetDateTime checkedAt,
            boolean consistent,
            int discrepancies,
            List<BalanceMismatch> balanceMismatches,
            List<UUID> unbalancedTransfers,
            List<UUID> malformedTransfers,
            List<UUID> inconsistentReversals,
            List<UUID> overdrawnWallets,
            long totalBalance) {}

    private final JdbcClient jdbc;
    private final AtomicInteger discrepancyGauge;

    public ReconciliationService(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.discrepancyGauge = registry.gauge("ledgerx.reconciliation.discrepancies", new AtomicInteger());
    }

    /** One snapshot (REPEATABLE READ) so concurrent transfers cannot produce false positives. */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Report run() {
        List<BalanceMismatch> mismatches = jdbc.sql("""
                SELECT w.id AS wallet_id, w.balance AS balance, COALESCE(SUM(l.amount), 0)::bigint AS ledger_sum
                FROM wallets w LEFT JOIN ledger_entries l ON l.wallet_id = w.id
                GROUP BY w.id, w.balance
                HAVING w.balance <> COALESCE(SUM(l.amount), 0)
                """).query(BalanceMismatch.class).list();

        List<UUID> unbalanced = jdbc.sql("""
                SELECT transfer_id FROM ledger_entries GROUP BY transfer_id HAVING SUM(amount) <> 0
                """).query(UUID.class).list();

        // Every transfer must have exactly one debit and one credit entry.
        List<UUID> malformed = jdbc.sql("""
                SELECT t.id FROM transfers t LEFT JOIN ledger_entries l ON l.transfer_id = t.id
                GROUP BY t.id HAVING COUNT(l.id) <> 2
                """).query(UUID.class).list();

        // A reversal must mirror the transfer it undoes: same amount, opposite direction.
        List<UUID> inconsistentReversals = jdbc.sql("""
                SELECT r.id FROM transfers r JOIN transfers o ON o.id = r.reverses_transfer_id
                WHERE r.amount <> o.amount OR r.from_wallet_id <> o.to_wallet_id OR r.to_wallet_id <> o.from_wallet_id
                """).query(UUID.class).list();

        List<UUID> overdrawn = jdbc.sql("SELECT id FROM wallets WHERE kind = 'USER' AND balance < 0")
                .query(UUID.class).list();

        // Money is only ever moved, never created, so all balances must sum to zero.
        long total = jdbc.sql("SELECT COALESCE(SUM(balance), 0)::bigint FROM wallets")
                .query(Long.class).single();

        int discrepancies = mismatches.size() + unbalanced.size() + malformed.size() + inconsistentReversals.size() + overdrawn.size()
                + (total != 0 ? 1 : 0);
        discrepancyGauge.set(discrepancies);
        return new Report(OffsetDateTime.now(), discrepancies == 0, discrepancies,
                mismatches, unbalanced, malformed, inconsistentReversals, overdrawn, total);
    }
}
