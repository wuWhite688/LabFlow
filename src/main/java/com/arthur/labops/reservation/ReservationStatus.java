package com.arthur.labops.reservation;

public enum ReservationStatus {
    /** Submitted, waiting for a teacher/admin decision. Overlapping requests may coexist. */
    PENDING,
    /** Approved and free of charge. Terminal-side confirmed state for unpriced equipment. */
    APPROVED,
    /**
     * Approved but not yet paid. Holds the calendar slot for a short payment
     * window; when that elapses the reservation is closed and the slot returns.
     */
    AWAITING_PAYMENT,
    /** Paid and confirmed. The priced-equipment counterpart of APPROVED. */
    PAID,
    /**
     * Cancelled by the user after paying; waiting for the refund callback. The
     * slot is already released, but the reservation is not closed yet.
     */
    REFUNDING,
    REJECTED,
    CANCELLED,
    EXPIRED,
    COMPLETED
}
