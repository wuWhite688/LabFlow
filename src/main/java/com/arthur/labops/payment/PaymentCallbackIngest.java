package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The single door channel callbacks come through, whether they arrive over HTTP
 * or straight from the in-process simulator.
 *
 * <p>Deliberately outside {@link PaymentCallbackService}'s transaction. When two
 * deliveries of the same callback race, the unique index on the ledger rejects
 * the loser and its transaction is already doomed — a transaction marked
 * rollback-only cannot be talked out of it from the inside. Catching out here,
 * after that transaction has ended, is what turns a lost race into a calm
 * "already recorded" instead of a 500 the channel would keep retrying.
 */
@Service
public class PaymentCallbackIngest {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackIngest.class);

    private final PaymentCallbackService callbackService;

    public PaymentCallbackIngest(PaymentCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    public PaymentCallbackResult ingest(PaymentCallbackRequest request) {
        try {
            return callbackService.handle(request);
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Payment callback lost the idempotency race orderNo={} idempotencyKey={}",
                    request.orderNo(), request.idempotencyKey());
            return new PaymentCallbackResult(request.orderNo(), false, null);
        }
    }
}
