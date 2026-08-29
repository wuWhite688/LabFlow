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
    private final PaymentOrderRepository orderRepository;
    private final PaymentRequestStateService stateService;
    private final SimulatedPaymentChannel channel;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentDispatchService(PaymentRequestRepository requestRepository,
                                  PaymentOrderRepository orderRepository,
                                  PaymentRequestStateService stateService,
                                  SimulatedPaymentChannel channel,
                                  ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.stateService = stateService;
        this.channel = channel;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Drops an intent that no longer applies, so the retry job stops offering it.
     *
     * <p>Cleanup, not the safety mechanism. {@link #stillApplies} on the sending
     * path is what actually makes a stale attempt harmless — exactly the split
     * the reservation deadlines use, and for the same reason: a disarm can be
     * missed, a guard at the point of firing cannot.
     */
    @Transactional
    public void abandonIntent(String idempotencyKey) {
        // Joins the caller's transaction rather than taking its own. Closing an
        // order and dropping its intent are one decision: if the close rolls back,
        // an intent abandoned in a separate transaction would survive it and the
        // user could never pay.
        requestRepository.findByIdempotencyKey(idempotencyKey)
                .filter(PaymentRequest::markObsolete)
                .ifPresent(request -> log.info("Outbound payment intent abandoned key={}", idempotencyKey));
    }

    /**
     * Records the intent to send. Presenting a key that already exists is a no-op:
     * the intent is already owed, and asking twice must not owe it twice.
     *
     * @return true when this call created the request
     */
    @Transactional(readOnly = true)
    public boolean hasIntent(String idempotencyKey) {
        return requestRepository.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    /**
     * Is this intent still worth sending? Read fresh, because the answer changes
     * between the attempt that failed and the attempt that retries.
     */
    private boolean stillApplies(PaymentRequest request) {
        PaymentOrder order = orderRepository.findByOrderNo(request.getOrderNo()).orElse(null);
        if (order == null) {
            return false;
        }
        return request.getType() == PaymentTransactionType.REFUND
                // Refunding is owed while the channel still holds money for us.
                ? order.refundableCents() > 0
                // Charging is owed only while the order is still waiting to be paid.
                : order.getStatus() == PaymentOrderStatus.AWAITING_PAYMENT;
    }

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
        if (!stillApplies(request)) {
            // Reliable delivery is not the same as correct delivery. Retrying a
            // payment for a reservation that has since expired charges for a slot
            // the platform has already handed to somebody else; the compensating
            // refund would make that recoverable, but taking money we already know
            // we must return is not something to do on purpose.
            log.info("Outbound payment request no longer applies key={} type={} orderNo={}",
                    idempotencyKey, request.getType(), request.getOrderNo());
            stateService.markObsolete(idempotencyKey);
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
