package com.arthur.labops.reservation;

import java.time.Instant;

import com.arthur.labops.equipment.Equipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "equipment_reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private Equipment equipment;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "requester_name", nullable = false, length = 80)
    private String requesterName;

    @Column(nullable = false, length = 500)
    private String purpose;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Deadline for the short payment window. Only set while the reservation is
     * AWAITING_PAYMENT; cleared once the money question is settled either way.
     */
    @Column(name = "payment_deadline")
    private Instant paymentDeadline;

    /**
     * Second-line lost-update detection. PESSIMISTIC_WRITE on state changes remains
     * the primary correctness guarantee; {@code @Version} makes concurrent writers
     * fail on any database, including H2 where {@code FOR UPDATE} does not serialize.
     */
    @Version
    private long version;

    protected Reservation() {
    }

    public Reservation(Equipment equipment, Long requesterId, String requesterName,
                       String purpose, Instant startTime, Instant endTime, Instant expiresAt) {
        this.equipment = equipment;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.purpose = purpose;
        this.startTime = startTime;
        this.endTime = endTime;
        this.status = ReservationStatus.PENDING;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt.isBefore(endTime) ? expiresAt : endTime;
    }

    public Long getId() { return id; }
    public Equipment getEquipment() { return equipment; }
    public Long getRequesterId() { return requesterId; }
    public String getRequesterName() { return requesterName; }
    public String getPurpose() { return purpose; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaymentDeadline() { return paymentDeadline; }
    public long getVersion() { return version; }

    public void approve() {
        status = ReservationStatus.APPROVED;
    }

    /** Approved, but the equipment costs money: hold the slot until {@code deadline}. */
    public void awaitPayment(Instant deadline) {
        status = ReservationStatus.AWAITING_PAYMENT;
        paymentDeadline = deadline;
    }

    public void markPaid() {
        status = ReservationStatus.PAID;
        paymentDeadline = null;
    }

    /**
     * Cancelled after payment. The slot is released immediately — a refund in
     * flight is no reason to keep the equipment blocked — but the reservation
     * stays open until the refund callback confirms it.
     */
    public void beginRefund() {
        status = ReservationStatus.REFUNDING;
        paymentDeadline = null;
    }

    /** The refund landed; the reservation is finally closed. */
    public void completeRefund() {
        status = ReservationStatus.CANCELLED;
    }

    /**
     * Closes an unpaid reservation whose payment window elapsed and returns the
     * slot. Same shape as {@link #expireIfPending(Instant)}: the caller holds the
     * row lock, and the guard re-checks state so a late timer cannot undo a
     * payment that landed first.
     */
    public boolean closeIfPaymentWindowElapsed(Instant now) {
        if (status == ReservationStatus.AWAITING_PAYMENT
                && paymentDeadline != null && !paymentDeadline.isAfter(now)) {
            status = ReservationStatus.EXPIRED;
            paymentDeadline = null;
            return true;
        }
        return false;
    }

    public void reject() {
        status = ReservationStatus.REJECTED;
    }

    public void cancel() {
        status = ReservationStatus.CANCELLED;
    }

    public void complete() {
        status = ReservationStatus.COMPLETED;
    }

    public boolean expireIfPending(Instant now) {
        if (status == ReservationStatus.PENDING
                && (!expiresAt.isAfter(now) || !endTime.isAfter(now))) {
            status = ReservationStatus.EXPIRED;
            return true;
        }
        return false;
    }
}
