package com.arthur.labops.reservation;

public enum ReservationClosure {
    /** Closed outright; no money was involved or none had moved yet. */
    CANCELLED,
    /** Money had moved: the reservation is in REFUNDING until the refund callback lands. */
    REFUND_PENDING,
    /** Nothing to do — already closed, or already on its way out. */
    NOT_CLOSEABLE
}
