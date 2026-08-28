package com.arthur.labops.reservation.expiry;

import java.time.Instant;

/**
 * Published inside the reservation transaction, acted on after it commits.
 *
 * <p>Arming after commit is what stops a rolled-back reservation from leaving a
 * timer behind; disarming after commit is the mirror of that — a cancellation
 * that never committed must not delete a deadline that is still live.
 */
public record ReservationDeadlineEvent(
        ReservationDeadlineKind kind,
        Long reservationId,
        Instant deadline,
        Action action) {

    public enum Action {
        ARM,
        DISARM
    }

    public static ReservationDeadlineEvent arm(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        return new ReservationDeadlineEvent(kind, reservationId, deadline, Action.ARM);
    }

    public static ReservationDeadlineEvent disarm(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        return new ReservationDeadlineEvent(kind, reservationId, deadline, Action.DISARM);
    }
}
