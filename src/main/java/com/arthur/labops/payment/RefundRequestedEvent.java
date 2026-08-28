package com.arthur.labops.payment;

/**
 * Published inside the cancelling transaction, sent to the channel after it
 * commits. Calling out to a payment channel from inside an open transaction is
 * how you end up refunding a cancellation that then rolled back.
 */
public record RefundRequestedEvent(String orderNo, long amountCents) {
}
