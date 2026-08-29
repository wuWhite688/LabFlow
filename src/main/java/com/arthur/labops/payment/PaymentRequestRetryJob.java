package com.arthur.labops.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arthur.labops.payment.reconcile.PaymentDiscrepancyTicketService;

/**
 * Keeps trying the requests the channel has not accepted yet.
 *
 * <p>This is what makes a cancelled-and-paid reservation recoverable. Without it
 * a single failed outbound call strands the reservation in REFUNDING with the
 * money still at the channel — and reconciliation cannot find that, because the
 * request never arrived, so neither side has a refund and both sides agree.
 *
 * <p>Retrying is only safe because every attempt carries the same idempotency
 * key; without that this job would be a double-refund generator.
 */
@Component
public class PaymentRequestRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestRetryJob.class);

    private final PaymentRequestRepository requestRepository;
    private final PaymentDispatchService dispatchService;
    private final PaymentDiscrepancyTicketService ticketService;

    public PaymentRequestRetryJob(PaymentRequestRepository requestRepository,
                                  PaymentDispatchService dispatchService,
                                  PaymentDiscrepancyTicketService ticketService) {
        this.requestRepository = requestRepository;
        this.dispatchService = dispatchService;
        this.ticketService = ticketService;
    }

    @Scheduled(fixedDelayString = "${labops.payment.outbound.retry-interval:30s}")
    public void retryDueRequests() {
        List<String> due = new ArrayList<>(requestRepository.findDueKeys(Instant.now()));
        for (String key : due) {
            try {
                PaymentRequest abandoned = dispatchService.attempt(key);
                if (abandoned != null) {
                    // Out of retries. A human has to look at real money now, so this
                    // becomes a ticket rather than another line in a log nobody reads.
                    ticketService.raiseOutboundFailureTicket(abandoned);
                }
            } catch (RuntimeException exception) {
                log.warn("Retrying outbound payment request {} failed", key, exception);
            }
        }
    }
}
