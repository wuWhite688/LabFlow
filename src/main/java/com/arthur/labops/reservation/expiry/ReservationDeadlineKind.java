package com.arthur.labops.reservation.expiry;

/**
 * Which clock a scheduled reservation deadline belongs to. Both kinds ride the
 * same delay infrastructure but must never share a queue or a cancellation key,
 * or cancelling one would silently disarm the other.
 */
public enum ReservationDeadlineKind {

    /** Approval timeout for a PENDING reservation. */
    APPROVAL,

    /** Payment window for an AWAITING_PAYMENT reservation. */
    PAYMENT
}
