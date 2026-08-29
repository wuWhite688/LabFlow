package com.arthur.labops.reservation;

/**
 * The reservation state machine's view of the money side.
 *
 * <p>Declared here and implemented in {@code com.arthur.labops.payment} so the
 * dependency runs payment &rarr; reservation only. The reservation package must not
 * grow a compile-time dependency on the ledger, and the two services must not
 * form a Spring bean cycle.
 */
public interface ReservationPaymentGateway {

    /**
     * Opens the order a reservation must settle before it is confirmed.
     *
     * @return the order number, for audit detail
     */
    String openOrder(Reservation reservation, long amountCents);

    /** Closes an unpaid order because the payment window elapsed or the user cancelled. */
    void closeUnpaidOrder(Long reservationId);

    /**
     * Asks the channel to refund everything still held for this reservation.
     * The reservation only reaches CANCELLED when the refund callback lands.
     */
    void requestFullRefund(Long reservationId);
}
