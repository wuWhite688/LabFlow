package com.arthur.labops.payment.reconcile;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

/**
 * T+1: settle yesterday's books once today's has started. Day boundaries are UTC
 * to match how the channel cuts its bill.
 */
@Component
@ConditionalOnProperty(name = "labops.payment.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final SimulatedPaymentChannel channel;
    private final ReconciliationService reconciliationService;

    public ReconciliationJob(SimulatedPaymentChannel channel,
                             ReconciliationService reconciliationService) {
        this.channel = channel;
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${labops.payment.reconciliation.cron:0 30 1 * * *}", zone = "UTC")
    public void reconcileYesterday() {
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            channel.writeDailyBill(settlementDate);
            ReconciliationReport report = reconciliationService.reconcile(settlementDate);
            log.info("Scheduled reconciliation date={} balanced={} discrepancies={}",
                    settlementDate, report.balanced(), report.discrepancies().size());
        } catch (RuntimeException exception) {
            log.error("Scheduled reconciliation failed date={}", settlementDate, exception);
        }
    }
}
