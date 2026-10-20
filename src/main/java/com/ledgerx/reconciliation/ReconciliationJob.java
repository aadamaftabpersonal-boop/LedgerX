package com.ledgerx.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic audit. Inconsistencies are logged at ERROR and exposed as ledgerx_reconciliation_discrepancies. */
@Component
@ConditionalOnProperty(name = "ledgerx.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${ledgerx.reconciliation.interval:PT5M}",
            initialDelayString = "${ledgerx.reconciliation.initial-delay:PT1M}")
    public void run() {
        ReconciliationService.Report report = service.run();
        if (report.consistent()) {
            log.info("reconciliation ok");
        } else {
            log.error("reconciliation found {} discrepancies: balanceMismatches={} unbalancedTransfers={} "
                            + "malformedTransfers={} overdrawnWallets={} totalBalance={}",
                    report.discrepancies(), report.balanceMismatches(), report.unbalancedTransfers(),
                    report.malformedTransfers(), report.overdrawnWallets(), report.totalBalance());
        }
    }
}
