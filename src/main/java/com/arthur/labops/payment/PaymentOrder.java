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

    public void applyPayment(long cents) {
        this.paidCents += cents;
        this.status = PaymentOrderStatus.PAID;
        this.updatedAt = Instant.now();
    }

    public void applyRefund(long cents) {
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
