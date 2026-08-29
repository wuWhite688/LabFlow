package com.arthur.labops.payment;

public enum PaymentRequestStatus {
    /** Accepted locally, not yet acknowledged by the channel. */
    PENDING,
    /** The channel accepted it. Callbacks are what settle the money; this only says it was sent. */
    SENT,
    /** The last attempt failed. Still eligible for retry. */
    FAILED,
    /** Retried past the limit. A discrepancy ticket has been raised for a human. */
    ABANDONED
}
