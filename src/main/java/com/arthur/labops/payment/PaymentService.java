package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationPaymentGateway;

/**
 * The reservation state machine's entry point into the ledger.
 *
 * <p>Every method here runs inside the caller's transaction and inside its lock
 * order (Equipment &rarr; Reservation &rarr; PaymentOrder). Nothing here talks to the
 * channel directly: an outbound call is published as an event and made after
 * commit, so a rolled-back cancellation can never leave a refund in flight.
 */
@Service
public class PaymentService implements ReservationPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderRepository orderRepository;
    private final PaymentDispatchService dispatchService;

    public PaymentService(PaymentOrderRepository orderRepository,
                          PaymentDispatchService dispatchService) {
        this.orderRepository = orderRepository;
        this.dispatchService = dispatchService;
    }

    /**
     * Order numbers are derived from the reservation id rather than random, so a
     * reservation has exactly one order for its whole life and a replayed
     * scenario produces byte-identical bills.
     */
    public static String orderNoFor(Long reservationId) {
        return String.format("LF%08d", reservationId);
    }

    @Override
    @Transactional
    public String openOrder(Reservation reservation, long amountCents) {
        String orderNo = orderNoFor(reservation.getId());
        PaymentOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseGet(() -> orderRepository.save(new PaymentOrder(
                        orderNo,
                        reservation.getId(),
                        reservation.getEquipment().getId(),
                        reservation.getRequesterId(),
                        amountCents)));
        log.info("Payment order opened orderNo={} reservationId={} amountCents={}",
                orderNo, reservation.getId(), amountCents);
        return order.getOrderNo();
    }

    @Override
    @Transactional
    public void closeUnpaidOrder(Long reservationId) {
        PaymentOrder order = orderRepository.findByReservationIdForUpdate(reservationId).orElse(null);
        if (order == null || order.getStatus() != PaymentOrderStatus.AWAITING_PAYMENT) {
            return;
        }
        order.close();
        log.info("Payment order closed unpaid orderNo={} reservationId={}", order.getOrderNo(), reservationId);
    }

    @Override
    @Transactional
    public void requestFullRefund(Long reservationId) {
        PaymentOrder order = orderRepository.findByReservationIdForUpdate(reservationId).orElse(null);
        if (order == null) {
            return;
        }
        long refundable = order.refundableCents();
        if (refundable <= 0) {
            return;
        }
        // Durable and keyed. The channel call happens after this transaction
        // commits, and a failure there is retried rather than lost — a refund that
        // never left the building is invisible to reconciliation, because neither
        // side has it and both sides therefore agree.
        dispatchService.enqueue(
                order.getOrderNo(),
                PaymentTransactionType.REFUND,
                refundable,
                PaymentIdempotency.cancellationRefund(order.getOrderNo()));
    }
}
