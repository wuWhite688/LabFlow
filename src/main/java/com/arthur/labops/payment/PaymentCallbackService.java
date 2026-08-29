package com.arthur.labops.payment;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.payment.reconcile.PaymentDiscrepancyTicketService;
import com.arthur.labops.common.BusinessException;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.expiry.ReservationDeadlineEvent;
import com.arthur.labops.reservation.expiry.ReservationDeadlineKind;

/**
 * Applies one channel callback, exactly once.
 *
 * <p>Idempotency is two-layered. The lookup below absorbs the ordinary case — a
 * gateway redelivering because it never saw an acknowledgement — without
 * touching anything. The unique index on {@code idempotency_key} absorbs the
 * case the lookup structurally cannot: two deliveries in flight at the same
 * moment, both reading "not seen" before either writes. The loser's whole
 * transaction rolls back, which is the correct outcome — it applied nothing —
 * and {@link PaymentCallbackIngest} turns that into a quiet "already recorded"
 * rather than an error the channel would retry forever.
 *
 * <p>Locks in the platform-wide order — Equipment &rarr; Reservation &rarr; PaymentOrder.
 * The payment order is appended to the existing Equipment &rarr; Reservation order
 * rather than taken first: a callback and a cancellation touch the same three
 * rows from opposite ends of the system, and taking the order row first here
 * would reintroduce exactly the ABBA cycle the reservation path already fixed.
 */
@Service
public class PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);

    private final PaymentOrderRepository orderRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusService equipmentStatusService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentDispatchService dispatchService;
    private final PaymentRequestRepository requestRepository;
    private final PaymentDiscrepancyTicketService ticketService;

    public PaymentCallbackService(PaymentOrderRepository orderRepository,
                                  PaymentTransactionRepository transactionRepository,
                                  ReservationRepository reservationRepository,
                                  EquipmentRepository equipmentRepository,
                                  EquipmentStatusService equipmentStatusService,
                                  AuditLogService auditLogService,
                                  ApplicationEventPublisher eventPublisher,
                                  PaymentDispatchService dispatchService,
                                  PaymentRequestRepository requestRepository,
                                  PaymentDiscrepancyTicketService ticketService) {
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.reservationRepository = reservationRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentStatusService = equipmentStatusService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
        this.dispatchService = dispatchService;
        this.requestRepository = requestRepository;
        this.ticketService = ticketService;
    }

    @Transactional
    public PaymentCallbackResult handle(PaymentCallbackRequest request) {
        PaymentOrder unlocked = orderRepository.findByOrderNo(request.orderNo())
                .orElseThrow(() -> new BusinessException(
                        "PAYMENT_ORDER_NOT_FOUND", "支付订单不存在", HttpStatus.NOT_FOUND));

        equipmentRepository.findByIdForUpdate(unlocked.getEquipmentId());
        Reservation reservation = reservationRepository.findByIdForUpdate(unlocked.getReservationId())
                .orElseThrow(() -> new BusinessException(
                        "RESERVATION_NOT_FOUND", "预约不存在", HttpStatus.NOT_FOUND));
        PaymentOrder order = orderRepository.findByOrderNoForUpdate(request.orderNo())
                .orElseThrow(() -> new BusinessException(
                        "PAYMENT_ORDER_NOT_FOUND", "支付订单不存在", HttpStatus.NOT_FOUND));

        if (!request.succeeded()) {
            // The channel is reporting an outcome, not just an event. A failed
            // attempt moved no money, so it has no place in a ledger whose whole
            // meaning is "money that moved" — and folding it onto the order would
            // mark an unpaid reservation as paid. Recorded in the audit trail and
            // acknowledged, so the channel stops retrying it.
            auditLogService.recordSystem("PAYMENT_CALLBACK_UNSUCCESSFUL", "RESERVATION",
                    unlocked.getReservationId(),
                    "渠道回调状态为 " + request.status() + "，订单号 " + request.orderNo() + "，不计入流水");
            log.info("Payment callback ignored, channel status={} orderNo={}",
                    request.status(), request.orderNo());
            reopenRejectedRequest(request);
            return new PaymentCallbackResult(request.orderNo(), false, unlocked.getStatus());
        }

        if (transactionRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            log.info("Payment callback ignored as replay orderNo={} idempotencyKey={}",
                    request.orderNo(), request.idempotencyKey());
            return new PaymentCallbackResult(request.orderNo(), false, order.getStatus());
        }

        if (!amountIsCoherent(request, order)) {
            // The channel moved money the platform cannot attribute to this order.
            // Booking it anyway is how one cent settles a 6000-cent reservation, and
            // how a refund grows past the payment it is refunding. So it stays off
            // the books deliberately — which is the one case where the two ledgers
            // are *supposed* to disagree, and reconciliation raises the ticket
            // precisely because the channel has a transaction we do not.
            auditLogService.recordSystem("PAYMENT_CALLBACK_AMOUNT_REJECTED", "RESERVATION",
                    order.getReservationId(),
                    "渠道回调金额 " + request.amountCents() + " 分与订单不符（" + request.type()
                            + "，订单号 " + request.orderNo() + "，待付 " + order.outstandingCents()
                            + " 分，可退 " + order.refundableCents() + " 分），不计入流水");
            log.warn("Payment callback rejected on amount orderNo={} type={} amountCents={}",
                    request.orderNo(), request.type(), request.amountCents());
            return new PaymentCallbackResult(request.orderNo(), false, order.getStatus());
        }

        transactionRepository.saveAndFlush(new PaymentTransaction(
                request.orderNo(),
                request.idempotencyKey(),
                request.type(),
                request.amountCents(),
                request.channelTxnId(),
                request.status(),
                request.occurredAt()));

        applyToLedgerAndReservation(request, order, reservation);
        equipmentStatusService.sync(order.getEquipmentId());

        log.info("Payment callback recorded orderNo={} type={} amountCents={} orderStatus={}",
                request.orderNo(), request.type(), request.amountCents(), order.getStatus());
        return new PaymentCallbackResult(request.orderNo(), true, order.getStatus());
    }

    /**
     * Does this amount mean anything against this order?
     *
     * <p>The platform sells one thing per order and takes one payment for it, so
     * the only coherent payment is the whole outstanding amount, and no refund can
     * exceed what the channel is still holding. The exception is money that
     * arrives after the window closed: it is refunded in full whatever it is, so
     * no amount there can leave the books wrong.
     */
    private boolean amountIsCoherent(PaymentCallbackRequest request, PaymentOrder order) {
        if (request.type() == PaymentTransactionType.REFUND) {
            return order.acceptsRefund(request.amountCents());
        }
        if (order.getStatus() == PaymentOrderStatus.CLOSED) {
            return true;
        }
        return order.acceptsPayment(request.amountCents());
    }

    /**
     * The channel accepted a request and has now reported that it failed.
     *
     * <p>Not recording it as money is only half the answer. The outbound request
     * was marked SENT when the channel took it, and SENT counts as settled, so
     * without this the refund is never sent again — the reservation stays in
     * REFUNDING for good while both ledgers hold only the original payment and
     * therefore agree with each other.
     *
     * <p>The next attempt goes out under a fresh channel key. The intent key stays
     * put, because it is what stops a retry from becoming a second payment; but
     * the channel has given a final answer about the attempt presented under the
     * old one, and asking again with it would be a no-op at any gateway that
     * honours idempotency.
     */
    private void reopenRejectedRequest(PaymentCallbackRequest request) {
        PaymentRequest outbound = requestRepository
                .findFirstByOrderNoAndTypeAndStatusOrderByIdDesc(
                        request.orderNo(), request.type(), PaymentRequestStatus.SENT)
                .orElse(null);
        if (outbound == null) {
            // Nothing of ours was in flight — a payment the user made in the
            // channel's own app, for instance. There is no intent to reopen.
            return;
        }
        String reason = "渠道回调状态 " + request.status() + "，交易号 " + request.channelTxnId();
        if (outbound.reopenAfterChannelRejection(reason)) {
            log.info("Outbound payment request reopened after channel rejection key={} channelAttempt={}",
                    outbound.getIdempotencyKey(), outbound.getChannelAttempt());
            eventPublisher.publishEvent(new PaymentRequestQueuedEvent(outbound.getIdempotencyKey()));
        } else {
            // Out of attempts with real money still owed. Nothing automated is left.
            ticketService.raiseOutboundHaltedTicket(outbound, reason + "，重试预算已用尽");
        }
    }

    private void applyToLedgerAndReservation(PaymentCallbackRequest request,
                                             PaymentOrder order,
                                             Reservation reservation) {
        if (request.type() == PaymentTransactionType.PAYMENT) {
            if (order.getStatus() == PaymentOrderStatus.CLOSED) {
                applyLatePayment(request, order, reservation);
                return;
            }
            Instant paymentDeadline = reservation.getPaymentDeadline();
            order.applyPayment(request.amountCents());
            if (reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT) {
                reservation.markPaid();
                eventPublisher.publishEvent(ReservationDeadlineEvent.disarm(
                        ReservationDeadlineKind.PAYMENT, reservation.getId(), paymentDeadline));
            }
            auditLogService.recordSystem("PAYMENT_SUCCEEDED", "RESERVATION", reservation.getId(),
                    "支付成功 订单号 " + order.getOrderNo() + "，金额 " + request.amountCents() + " 分");
        } else {
            order.applyRefund(request.amountCents());
            // A partial refund leaves a PAID reservation paid. Only a refund that
            // clears the balance closes a cancellation that is waiting on it.
            if (reservation.getStatus() == ReservationStatus.REFUNDING && order.refundableCents() <= 0) {
                reservation.completeRefund();
            }
            auditLogService.recordSystem("REFUND_SUCCEEDED", "RESERVATION", reservation.getId(),
                    "退款到账 订单号 " + order.getOrderNo() + "，金额 " + request.amountCents() + " 分");
        }
    }

    /**
     * The money arrived after the payment window had already closed the
     * reservation and given the slot back.
     *
     * <p>The reservation stays closed — someone else may hold that slot by now, and
     * resurrecting it would double-book the equipment. But the payment is real, so
     * it is recorded, the order moves to REFUND_DUE to say the platform is holding
     * money it should not be, and a compensating refund is queued. Nothing here can
     * be left to reconciliation: the channel collected and the ledger recorded, so
     * both sides agree and the books look perfect while the user is out of pocket.
     */
    private void applyLatePayment(PaymentCallbackRequest request, PaymentOrder order, Reservation reservation) {
        order.applyLatePayment(request.amountCents());
        dispatchService.enqueue(
                order.getOrderNo(),
                PaymentTransactionType.REFUND,
                request.amountCents(),
                PaymentIdempotency.latePaymentRefund(order.getOrderNo()));
        auditLogService.recordSystem("PAYMENT_ARRIVED_LATE", "RESERVATION", reservation.getId(),
                "支付在窗口关闭后到账 订单号 " + order.getOrderNo() + "，金额 " + request.amountCents()
                        + " 分，已发起补偿退款");
    }
}
