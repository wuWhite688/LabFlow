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

/** Applies one channel callback, exactly once where money moved. */
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
            auditLogService.recordSystem("PAYMENT_CALLBACK_UNSUCCESSFUL", "RESERVATION",
                    unlocked.getReservationId(),
                    "渠道回调状态为 " + request.status() + "，订单号 " + request.orderNo() + "，不计入流水");
            log.info("Payment callback ignored, channel status={} orderNo={} attemptKey={}",
                    request.status(), request.orderNo(), request.idempotencyKey());
            reopenRejectedRequest(request);
            return new PaymentCallbackResult(request.orderNo(), false, unlocked.getStatus());
        }

        if (transactionRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            log.info("Payment callback ignored as replay orderNo={} idempotencyKey={}",
                    request.orderNo(), request.idempotencyKey());
            return new PaymentCallbackResult(request.orderNo(), false, order.getStatus());
        }

        if (!amountIsCoherent(request, order)) {
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
     * Reopen only the request whose <em>current</em> channel key is named by this
     * FAILED outcome. That covers delayed SENT callbacks, synchronous PENDING
     * callbacks, and a late definitive answer after a transport failure left the
     * request FAILED. Once the first rejection advances #0 to #1, any duplicate or
     * delayed #0 rejection no longer matches and is a no-op.
     */
    private void reopenRejectedRequest(PaymentCallbackRequest request) {
        PaymentRequest outbound = requestRepository.findByOrderNoOrderByIdAsc(request.orderNo()).stream()
                .filter(candidate -> candidate.getType() == request.type())
                .filter(candidate -> candidate.matchesChannelKey(request.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (outbound == null) {
            log.info("Ignoring stale or unowned channel rejection orderNo={} callbackKey={}",
                    request.orderNo(), request.idempotencyKey());
            return;
        }

        String reason = "渠道回调状态 " + request.status() + "，交易号 " + request.channelTxnId();
        if (outbound.reopenAfterChannelRejection(reason)) {
            log.info("Outbound payment request reopened after channel rejection key={} channelAttempt={}",
                    outbound.getIdempotencyKey(), outbound.getChannelAttempt());
            eventPublisher.publishEvent(new PaymentRequestQueuedEvent(outbound.getIdempotencyKey()));
        } else if (outbound.getStatus() == PaymentRequestStatus.ABANDONED) {
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
            if (reservation.getStatus() == ReservationStatus.REFUNDING && order.refundableCents() <= 0) {
                reservation.completeRefund();
            }
            auditLogService.recordSystem("REFUND_SUCCEEDED", "RESERVATION", reservation.getId(),
                    "退款到账 订单号 " + order.getOrderNo() + "，金额 " + request.amountCents() + " 分");
        }
    }

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
