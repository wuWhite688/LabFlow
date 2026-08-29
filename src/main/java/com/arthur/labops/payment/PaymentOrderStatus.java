package com.arthur.labops.payment;

public enum PaymentOrderStatus {
    /** Order opened, waiting for the channel to report a successful payment. */
    AWAITING_PAYMENT,
    PAID,
    /**
     * Money was collected against a reservation that is already closed — a
     * payment that arrived after its window elapsed. The platform holds it and
     * owes it back; this is not a settled order.
     */
    REFUND_DUE,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /** Closed without payment (the payment window elapsed). */
    CLOSED
}
