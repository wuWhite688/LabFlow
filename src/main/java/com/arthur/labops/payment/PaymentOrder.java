package com.arthur.labops.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Local running total for one reservation's money. The authoritative history is
 * {@link PaymentTransaction}; this row is the folded-up view of it, so every
 * mutation here is driven by an accepted callback and never by a client request.
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private Long reservationId;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "payer_id", nullable = false)
    private Long payerId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "paid_cents", nullable = false)
    private long paidCents;

    @Column(name = "refunded_cents", nullable = false)
    private long refundedCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PaymentOrder() {
    }

    public PaymentOrder(String orderNo, Long reservationId, Long equipmentId, Long payerId, long amountCents) {
        this.orderNo = orderNo;
        this.reservationId = reservationId;
        this.equipmentId = equipmentId;
        this.payerId = payerId;
        this.amountCents = amountCents;
        this.status = PaymentOrderStatus.AWAITING_PAYMENT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getReservationId() { return reservationId; }
    public Long getEquipmentId() { return equipmentId; }
    public Long getPayerId() { return payerId; }
    public long getAmountCents() { return amountCents; }
    public long getPaidCents() { return paidCents; }
    public long getRefundedCents() { return refundedCents; }
    public PaymentOrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    /** Net amount the channel should be holding for this order. */
    public long netCents() {
        return paidCents - refundedCents;
    }

    public long refundableCents() {
        return paidCents - refundedCents;
    }

    /** What is still owed before this order is settled. */
    public long outstandingCents() {
        return amountCents - paidCents;
    }

    /**
     * The platform sells one thing per order and takes one payment for it. Partial
     * and split payments are deliberately out of scope, so the only coherent
     * amount is the one still outstanding — anything else is money the platform
     * cannot attribute, and attributing it anyway is how a single cent settles a
     * 6000-cent booking.
     */
    public boolean acceptsPayment(long cents) {
        return cents == outstandingCents();
    }

    /** A refund can never exceed what the channel is still holding for us. */
    public boolean acceptsRefund(long cents) {
        return cents > 0 && cents <= refundableCents();
    }

    public void applyPayment(long cents) {
        if (!acceptsPayment(cents)) {
            // The caller is expected to have asked first and answered the channel
            // without applying anything. Reaching here is a bug, not bad input.
            throw new IllegalArgumentException(
                    "支付金额 " + cents + " 分与订单待付金额 " + outstandingCents() + " 分不符");
        }
        this.paidCents += cents;
        this.status = paidCents >= amountCents ? PaymentOrderStatus.PAID : PaymentOrderStatus.AWAITING_PAYMENT;
        this.updatedAt = Instant.now();
    }

    /**
     * Money that arrived after the order was already closed. It is real and has to
     * be on the books, but the order is not settled by it — the platform is
     * holding money for a reservation that no longer exists and owes it back.
     * Folding this on as an ordinary payment is what produces the silent
     * "reservation EXPIRED, order PAID, money kept" state.
     */
    public void applyLatePayment(long cents) {
        this.paidCents += cents;
        this.status = PaymentOrderStatus.REFUND_DUE;
        this.updatedAt = Instant.now();
    }

    public void applyRefund(long cents) {
        if (!acceptsRefund(cents)) {
            throw new IllegalArgumentException(
                    "退款金额 " + cents + " 分超过可退金额 " + refundableCents() + " 分");
        }
        this.refundedCents += cents;
        this.status = this.refundedCents >= this.paidCents
                ? PaymentOrderStatus.REFUNDED
                : PaymentOrderStatus.PARTIALLY_REFUNDED;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = PaymentOrderStatus.CLOSED;
        this.updatedAt = Instant.now();
    }
}
