package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.PaymentDiscrepancyTicketService;

/** Everything the platform sends to the channel goes through here. */
@Service
public class PaymentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDispatchService.class);

    private enum Applicability {
        OWED,
        SPENT,
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

    @Transactional
    public void abandonIntent(String idempotencyKey) {
        requestRepository.findByIdempotencyKeyForUpdate(idempotencyKey)
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
                return Applicability.SPENT;
            }
            return request.getAmountCents() <= refundable ? Applicability.OWED : Applicability.DRIFTED;
        }
        if (order.getStatus() != PaymentOrderStatus.AWAITING_PAYMENT) {
            return Applicability.SPENT;
        }
        return order.acceptsPayment(request.getAmountCents()) ? Applicability.OWED : Applicability.DRIFTED;
    }

    /**
     * Records an intent once. Asking again for an existing unsettled intent nudges
     * it immediately instead of waiting for the scheduled retry scan.
     *
     * <p>The order row is locked first and serves as the mutex for creating intents
     * on that order. Locking the request row instead is not an option when the row
     * may not exist yet: {@code FOR UPDATE} on a missing row takes a gap lock, and
     * two concurrent creations would then deadlock on insert. The unique index on
     * {@code idempotency_key} remains the last line of defence.
     *
     * <p>Lock order is payment_order then payment_request, matching the callback
     * path. Nothing here may go back and take an equipment or reservation lock.
     */
    @Transactional
    public boolean enqueue(String orderNo, PaymentTransactionType type, long amountCents, String idempotencyKey) {
        orderRepository.findByOrderNoForUpdate(orderNo);
        PaymentRequest existing = requestRepository.findByIdempotencyKeyForUpdate(idempotencyKey).orElse(null);
        if (existing != null) {
            log.info("Outbound payment request already queued key={} status={}",
                    idempotencyKey, existing.getStatus());
            if (!existing.isSettled()) {
                eventPublisher.publishEvent(new PaymentRequestQueuedEvent(idempotencyKey));
            }
            return false;
        }
        requestRepository.saveAndFlush(new PaymentRequest(orderNo, idempotencyKey, type, amountCents));
        eventPublisher.publishEvent(new PaymentRequestQueuedEvent(idempotencyKey));
        return true;
    }

    /**
     * Attempts one send. Two callers may race safely because they present the same
     * channel key. The completion write is also keyed to that exact attempt, so an
     * immediate callback that advances the request cannot be overwritten when the
     * older channel call returns.
     *
     * <p>Deliberately not transactional, and the channel call deliberately holds no
     * row lock: a synchronous callback arrives on another thread and needs the same
     * row, so holding it here would make the call wait on itself. The completion
     * write takes the lock afterwards, in its own short transaction.
     */
    public PaymentRequest attempt(String idempotencyKey) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null || request.isSettled()) {
            return null;
        }
        Applicability applicability = applicability(request);
        if (applicability != Applicability.OWED) {
            log.info("Outbound payment request no longer applies key={} type={} orderNo={} verdict={}",
                    idempotencyKey, request.getType(), request.getOrderNo(), applicability);
            stateService.markObsolete(idempotencyKey);
            if (applicability == Applicability.DRIFTED) {
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
            stateService.markSent(idempotencyKey, channelKey);
            return null;
        } catch (RuntimeException exception) {
            log.warn("Outbound payment request failed key={} channelKey={} type={} orderNo={}",
                    idempotencyKey, channelKey, request.getType(), request.getOrderNo(), exception);
            return stateService.markFailed(idempotencyKey, channelKey, exception.toString());
        }
    }
}
