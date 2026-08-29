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

/**
 * Local payment ledger. Exactly one row per accepted channel callback.
 *
 * <p>{@code idempotency_key} carries a unique index, so a replayed callback loses
 * the insert at the database rather than at an application-level "have I seen
 * this?" check. The read-then-write check would still let two concurrent
 * deliveries of the same callback both pass.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentTransactionType type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "channel_txn_id", nullable = false, length = 80)
    private String channelTxnId;

    @Column(name = "channel_status", nullable = false, length = 30)
    private String channelStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(String orderNo, String idempotencyKey, PaymentTransactionType type,
                              long amountCents, String channelTxnId, String channelStatus, Instant occurredAt) {
        this.orderNo = orderNo;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.amountCents = amountCents;
        this.channelTxnId = channelTxnId;
        this.channelStatus = channelStatus;
        this.occurredAt = occurredAt;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentTransactionType getType() { return type; }
    public long getAmountCents() { return amountCents; }
    public String getChannelTxnId() { return channelTxnId; }
    public String getChannelStatus() { return channelStatus; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }

    /** Signed contribution to the order's net balance. */
    public long signedCents() {
        return type == PaymentTransactionType.REFUND ? -amountCents : amountCents;
    }
}
