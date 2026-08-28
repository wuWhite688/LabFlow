package com.arthur.labops.reservation;

import java.util.EnumSet;
import java.util.Set;

/**
 * The status groupings the rest of the platform reasons about.
 *
 * <p>They used to live as private constants in {@code ReservationService}, but the
 * payment states made the same three questions matter in four places (quota,
 * calendar occupancy, equipment status, retirement guard). One copy each, so the
 * answers cannot drift apart.
 */
public final class ReservationStatuses {

    /**
     * Counts against the per-user quota: everything not yet closed. REFUNDING is
     * in here even though it no longer holds a slot — the reservation still has an
     * open obligation until the refund lands.
     */
    public static final Set<ReservationStatus> QUOTA = Set.copyOf(EnumSet.of(
            ReservationStatus.PENDING,
            ReservationStatus.APPROVED,
            ReservationStatus.AWAITING_PAYMENT,
            ReservationStatus.PAID,
            ReservationStatus.REFUNDING));

    /**
     * Holds the calendar slot. Overlapping PENDING requests may still coexist,
     * because approval is what commits the slot.
     *
     * <p>AWAITING_PAYMENT is in here deliberately: an approved-but-unpaid
     * reservation blocks the slot, which is exactly why its window is short and
     * why timing out has to give the slot back. REFUNDING is out — the user has
     * already cancelled, and holding the equipment hostage until an async refund
     * settles would punish everyone else for the channel's latency.
     */
    public static final Set<ReservationStatus> OCCUPIED = Set.copyOf(EnumSet.of(
            ReservationStatus.APPROVED,
            ReservationStatus.AWAITING_PAYMENT,
            ReservationStatus.PAID));

    /** Confirmed for use right now: what drives equipment into IN_USE. */
    public static final Set<ReservationStatus> CONFIRMED = Set.copyOf(EnumSet.of(
            ReservationStatus.APPROVED,
            ReservationStatus.PAID));

    /** Blocks retiring the equipment. Same membership as {@link #QUOTA}, different question. */
    public static final Set<ReservationStatus> OPEN = QUOTA;

    private ReservationStatuses() {
    }
}
