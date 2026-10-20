package com.ledgerx.api;

import com.ledgerx.transfer.TransferException;
import com.ledgerx.transfer.TransferResult;
import com.ledgerx.transfer.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Times every transfer request and tags it with its outcome, which gives rate, error ratio and latency
 * percentiles per outcome: ledgerx_transfer_seconds_{count,sum,bucket}{outcome,strategy}.
 */
@Component
public class TransferMetrics {

    private static final Logger log = LoggerFactory.getLogger(TransferMetrics.class);

    private final MeterRegistry registry;
    private final TransferService transfers;

    public TransferMetrics(MeterRegistry registry, TransferService transfers) {
        this.registry = registry;
        this.transfers = transfers;
    }

    public TransferResult observe(Supplier<TransferResult> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            TransferResult result = call.get();
            outcome = result.replayed() ? "replayed" : "success";
            return result;
        } catch (TransferException.InsufficientFunds e) {
            outcome = "insufficient_funds";
            throw e;
        } catch (TransferException.IdempotencyConflict e) {
            outcome = "idempotency_conflict";
            throw e;
        } catch (TransferException e) {
            outcome = "rejected";
            throw e;
        } finally {
            sample.stop(Timer.builder("ledgerx.transfer")
                    .description("Transfer request duration by outcome")
                    .tag("outcome", outcome)
                    .tag("strategy", transfers.lockStrategy().name())
                    .publishPercentileHistogram()
                    .register(registry));
            log.info("transfer outcome={}", outcome);
        }
    }
}
