package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.PaymentDiscrepancyTicketService;

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
 * channel key, and the channel collapses them. That is the point of the key, and
 * it is why this can be retried by a scheduler without a distributed lock.
 */
@Service
public class PaymentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDispatchService.class);

    /**
     * Whether an intent recorded earlier is still the right thing to send now.
     * Read fresh on every attempt, because the answer changes between the attempt
     * that failed and the attempt that retries.
     */
    private enum Applicability {
        /** Send it. */
        OWED,
        /** Nothing is owed any more. Drop it quietly; nobody needs to be told. */
        SPENT,
        /**
         * Something is still owed, but not the amount this request names. The
         * platform cannot work out on its own what the difference means, so this
         * stops and asks rather than sending a figure it knows to be stale.
         */
        DRIFTED
    }

    private final PaymentRequestRepository requestRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentRequestStateService stateService;
    private final PaymentDiscrepancyTicketService ticketService;
    private final SimulatedPaymentChannel channel;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentDispatchService(PaymentRequestRepository requestRepository,
                                  PaymentOrderRepository orderRepository,
                                  PaymentRequestStateService stateService,
                                  PaymentDiscrepancyTicketService ticketService,
                                  SimulatedPaymentChannel channel,
                                  ApplicationEventPublisher eventPublisher) {
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.stateService = stateService;
        this.ticketService = ticketService;
        this.channel = channel;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Drops an intent that no longer applies, so the retry job stops offering it.
     *
     * <p>Cleanup, not the safety mechanism. The applicability check on the sending
     * path is what actually makes a stale attempt harmless — exactly the split the
     * reservation deadlines use, and for the same reason: a disarm can be missed,
     * a guard at the point of firing is much harder to miss.
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

    @Transactional(readOnly = true)
    public boolean hasIntent(String idempotencyKey) {
        return requestRepository.findByIdempotencyKey(idempotencyKey).isPresent();
    }

    private Applicability applicability(PaymentRequest request) {
        PaymentOrder order = orderRepository.findByOrderNo(request.getOrderNo()).orElse(null);
        if (order == null) {
            return Applicability.SPENT;
        }
        if (request.getType() == PaymentTransactionType.REFUND) {
            long refundable = order.refundableCents();
            if (refundable <= 0) {
                // Somebody already gave it back. Nothing is owed, so nothing to do.
                return Applicability.SPENT;
            }
            // "Something is still refundable" is not the same as "this amount is
            // still refundable". An operator refunding part of it at the channel's
            // end between our attempts leaves a request naming a figure that would
            // now overshoot — and because the channel would record it too, both
            // sides would agree on a refund larger than the payment.
            return request.getAmountCents() <= refundable ? Applicability.OWED : Applicability.DRIFTED;
        }
        if (order.getStatus() != PaymentOrderStatus.AWAITING_PAYMENT) {
            // Charging is owed only while the order is still waiting to be paid.
            return Applicability.SPENT;
        }
        return order.acceptsPayment(request.getAmountCents()) ? Applicability.OWED : Applicability.DRIFTED;
    }

    /**
     * Records the intent to send. Presenting a key that already exists does not
     * create a second request — the intent is already owed, and asking twice must
     * not owe it twice — but it does nudge the existing one, so asking again for
     * something that is owed and stalled makes it move.
     *
     * @return true when this call created the request
     */
    @Transactional
    public boolean enqueue(String orderNo, PaymentTransactionType type, long amountCents, String idempotencyKey) {
        PaymentRequest existing = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            log.info("Outbound payment request already queued key={} status={}",
                    idempotencyKey, existing.getStatus());
            if (!existing.isSettled()) {
                // Still owed and not yet delivered — most likely reopened after the
                // channel rejected an earlier attempt. Waiting for the retry job
                // would be correct but slow, and the caller is standing there.
                eventPublisher.publishEvent(new PaymentRequestQueuedEvent(idempotencyKey));
            }
            return false;
        }
        requestRepository.saveAndFlush(new PaymentRequest(orderNo, idempotencyKey, type, amountCents));
        eventPublisher.publishEvent(new PaymentRequestQueuedEvent(idempotencyKey));
        return true;
    }

    /**
     * Attempts one send. Safe to call concurrently or repeatedly; the channel
     * deduplicates on the channel key.
     */
    public PaymentRequest attempt(String idempotencyKey) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null || request.isSettled()) {
            return null;
        }
        Applicability applicability = applicability(request);
        if (applicability != Applicability.OWED) {
            // Reliable delivery is not the same as correct delivery. Retrying a
            // payment for a reservation that has since expired charges for a slot
            // the platform has already handed to somebody else; the compensating
            // refund would make that recoverable, but taking money we already know
            // we must return is not something to do on purpose.
            log.info("Outbound payment request no longer applies key={} type={} orderNo={} verdict={}",
                    idempotencyKey, request.getType(), request.getOrderNo(), applicability);
            stateService.markObsolete(idempotencyKey);
            if (applicability == Applicability.DRIFTED) {
                // Real money is still owed, at an amount nobody here is entitled to
                // decide. Dropping it silently would strand it; sending it anyway
                // would overshoot. So it becomes somebody's job.
                ticketService.raiseOutboundHaltedTicket(request,
                        "金额已变化，请求所记金额 " + request.getAmountCents() + " 分不再与订单当前状态相符");
            }
            return null;
        }
        String channelKey = request.channelKey();
        try {
            if (request.getType() == PaymentTransactionType.REFUND) {
                channel.refund(request.getOrderNo(), request.getAmountCents(), channelKey);
            } else {
                channel.charge(request.getOrderNo(), request.getAmountCents(), channelKey);
            }
            stateService.markSent(idempotencyKey);
            return null;
        } catch (RuntimeException exception) {
            log.warn("Outbound payment request failed key={} channelKey={} type={} orderNo={}",
                    idempotencyKey, channelKey, request.getType(), request.getOrderNo(), exception);
            // Deliberately retried under the same channel key: a send that threw
            // may still have reached the channel, and a fresh key would turn that
            // uncertainty into a second real transaction.
            return stateService.markFailed(idempotencyKey, exception.toString());
        }
    }
}
