package com.arthur.labops.reservation.expiry;

import java.time.Instant;

public interface ReservationExpiryScheduler {

    void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline);

    /**
     * Drops a deadline that can no longer fire usefully.
     *
     * <p>Without this every decided or cancelled reservation left its timer armed
     * until the original deadline. The firing itself was harmless — the state
     * guard rejects it — but the queue (or the scheduler's task map) kept growing
     * with work that was already known to be pointless, and adding the payment
     * window would have doubled the rate of that growth.
     *
     * <p>Cancellation is best effort. It is a cleanup, not a correctness
     * mechanism: the state guard on the firing path stays the thing that makes a
     * late deadline safe.
     */
    void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline);

    default void schedule(Long reservationId, Instant expiresAt) {
        schedule(ReservationDeadlineKind.APPROVAL, reservationId, expiresAt);
    }
}
