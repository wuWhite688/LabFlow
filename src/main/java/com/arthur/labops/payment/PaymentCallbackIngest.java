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
    private final PaymentTransactionRepository transactionRepository;

    public PaymentCallbackIngest(PaymentCallbackService callbackService,
                                 PaymentTransactionRepository transactionRepository) {
        this.callbackService = callbackService;
        this.transactionRepository = transactionRepository;
    }

    public PaymentCallbackResult ingest(PaymentCallbackRequest request) {
        try {
            return callbackService.handle(request);
        } catch (DataIntegrityViolationException violation) {
            // Not every constraint violation is a duplicate. Now that the failed
            // transaction has rolled back, ask the database which one this was:
            // if the key is really there, someone else recorded it and there is
            // nothing to do. Otherwise this was some other broken write, and
            // swallowing it would report success for money we never recorded.
            if (transactionRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
                log.info("Payment callback lost the idempotency race orderNo={} idempotencyKey={}",
                        request.orderNo(), request.idempotencyKey());
                return new PaymentCallbackResult(request.orderNo(), false, null);
            }
            log.error("Payment callback failed on a constraint that is not the idempotency key orderNo={}",
                    request.orderNo(), violation);
            throw violation;
        }
    }
}
