package com.arthur.labops.payment;

import java.time.Duration;
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
 * One request the platform makes to the channel, and its delivery state.
 *
 * <p>{@code idempotencyKey} names the <em>intent</em>, not the attempt: "the full
 * payment for order X", "the cancellation refund for order X". Every attempt
 * presents the same key, so the channel collapses them into one transaction —
 * which is what lets this be retried at all without turning a stuck refund into
 * a double refund.
 */
@Entity
@Table(name = "payment_requests")
public class PaymentRequest {

    /** Cap on attempts before a human is asked to look. */
    static final int MAX_ATTEMPTS = 8;

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentRequestStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentRequest() {
    }

    public PaymentRequest(String orderNo, String idempotencyKey, PaymentTransactionType type, long amountCents) {
        this.orderNo = orderNo;
        this.idempotencyKey = idempotencyKey;
        this.type = type;
        this.amountCents = amountCents;
        this.status = PaymentRequestStatus.PENDING;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentTransactionType getType() { return type; }
    public long getAmountCents() { return amountCents; }
    public PaymentRequestStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }

    /** Nothing further will be sent for this request, whatever the reason. */
    public boolean isSettled() {
        return status == PaymentRequestStatus.SENT
                || status == PaymentRequestStatus.ABANDONED
                || status == PaymentRequestStatus.OBSOLETE;
    }

    /**
     * The intent no longer applies. Only reachable from a state where nothing has
     * been sent yet — once the channel has accepted a request, the money is in
     * flight and the answer is a refund, not an eraser.
     */
    public boolean markObsolete() {
        if (isSettled()) {
            return false;
        }
        this.status = PaymentRequestStatus.OBSOLETE;
        this.updatedAt = Instant.now();
        return true;
    }

    public void markSent() {
        this.status = PaymentRequestStatus.SENT;
        this.attempts += 1;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    /** @return true when this attempt exhausted the retry budget */
    public boolean markFailed(String error) {
        this.attempts += 1;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.updatedAt = Instant.now();
        if (attempts >= MAX_ATTEMPTS) {
            this.status = PaymentRequestStatus.ABANDONED;
            return true;
        }
        this.status = PaymentRequestStatus.FAILED;
        // Exponential backoff, capped. A channel that is down does not get hammered,
        // and a channel that blipped is retried quickly.
        long backoffMillis = Math.min(
                MAX_BACKOFF.toMillis(),
                BASE_BACKOFF.toMillis() * (1L << Math.min(attempts - 1, 20)));
        this.nextAttemptAt = Instant.now().plusMillis(backoffMillis);
        return false;
    }
}
