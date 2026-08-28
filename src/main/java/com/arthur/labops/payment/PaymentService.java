package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    public PaymentService(PaymentOrderRepository orderRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
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
        eventPublisher.publishEvent(new RefundRequestedEvent(order.getOrderNo(), refundable));
    }
}
