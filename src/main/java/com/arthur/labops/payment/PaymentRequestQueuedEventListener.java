package com.arthur.labops.payment;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaymentRequestQueuedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestQueuedEventListener.class);

    private final PaymentDispatchService dispatchService;
    private final TaskScheduler taskScheduler;

    public PaymentRequestQueuedEventListener(PaymentDispatchService dispatchService,
                                             TaskScheduler taskScheduler) {
        this.dispatchService = dispatchService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * First attempt, once the owing transaction has committed. Only an
     * optimisation over waiting for the retry job — the request is already
     * durable, so losing this attempt costs latency, not money.
     *
     * <p>Handed to the scheduler rather than run inline, for two reasons. An
     * outbound call to a payment channel has no business occupying the request
     * thread. And an {@code AFTER_COMMIT} listener runs with the just-completed
     * persistence context still bound to the thread: a new transaction opened
     * there reuses that finished EntityManager and fails with
     * "no transaction is known to be in progress" the moment the channel calls
     * back in-process and the callback tries to write. A pool thread starts
     * clean.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(PaymentRequestQueuedEvent event) {
        taskScheduler.schedule(() -> {
            try {
                dispatchService.attempt(event.idempotencyKey());
            } catch (RuntimeException exception) {
                log.warn("First dispatch attempt failed key={}; leaving it to the retry job",
                        event.idempotencyKey(), exception);
            }
        }, Instant.now());
    }
}
