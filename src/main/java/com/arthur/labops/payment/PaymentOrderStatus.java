package com.arthur.labops.payment;

public enum PaymentOrderStatus {
    /** Order opened, waiting for the channel to report a successful payment. */
    AWAITING_PAYMENT,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /** Closed without payment (the payment window elapsed). */
    CLOSED
}
