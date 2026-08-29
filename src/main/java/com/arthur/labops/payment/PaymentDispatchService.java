package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

/**
 * Everything the platform sends to the channel goes through here.
 *
 * <p>Enqueueing is transactional and joins the caller's transaction, so a
 * cancellation that rolls back never leaves a refund owed. Sending is
 * deliberately <em>not</em> inside that transaction, and holds no row lock while
 * the channel call is in flight: the callback can come straight back in-process
 * and would otherwise deadlock against the very rows it needs.
 *
 * <p>Two attempts racing is safe rather than prevented — both present the same
 * idempotency key, and the channel collapses them. That is the point of the key,
 * and it is why this can be retried by a scheduler without a distributed lock.
 */
@Service
public class PaymentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDispatchService.class);

    private final PaymentRequestRepository requestRepository;
    private final PaymentRequestStateService stateService;
    private final SimulatedPaymentChannel channel;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentDispatchService(PaymentRequestRepository requestRepository,
                                  PaymentRequestStateService stateService,
                                  SimulatedPaymentChannel channel,
                                  ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.stateService = stateService;
        this.channel = channel;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Records the intent to send. Presenting a key that already exists is a no-op:
     * the intent is already owed, and asking twice must not owe it twice.
     *
     * @return true when this call created the request
     */
    @Transactional
    public boolean enqueue(String orderNo, PaymentTransactionType type, long amountCents, String idempotencyKey) {
        if (requestRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.info("Outbound payment request already queued key={}", idempotencyKey);
            return false;
        }
        try {
            requestRepository.saveAndFlush(new PaymentRequest(orderNo, idempotencyKey, type, amountCents));
        } catch (DataIntegrityViolationException duplicate) {
            // Two callers raced the lookup above. The unique index settles it, and
            // the loser has nothing to do — the request exists either way.
            log.info("Outbound payment request lost the enqueue race key={}", idempotencyKey);
            throw duplicate;
        }
        eventPublisher.publishEvent(new PaymentRequestQueuedEvent(idempotencyKey));
        return true;
    }

    /**
     * Attempts one send. Safe to call concurrently or repeatedly; the channel
     * deduplicates on the idempotency key.
     */
    public PaymentRequest attempt(String idempotencyKey) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null || request.isSettled()) {
            return null;
        }
        try {
            if (request.getType() == PaymentTransactionType.REFUND) {
                channel.refund(request.getOrderNo(), request.getAmountCents(), idempotencyKey);
            } else {
                channel.charge(request.getOrderNo(), request.getAmountCents(), idempotencyKey);
            }
            stateService.markSent(idempotencyKey);
            return null;
        } catch (RuntimeException exception) {
            log.warn("Outbound payment request failed key={} type={} orderNo={}",
                    idempotencyKey, request.getType(), request.getOrderNo(), exception);
            return stateService.markFailed(idempotencyKey, exception.toString());
        }
    }
}
