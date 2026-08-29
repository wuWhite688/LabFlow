package com.arthur.labops.payment;

/**
 * Stable idempotency keys for outbound requests.
 *
 * <p>A key names an <em>intent</em> that may legitimately happen at most once for
 * an order, so every retry of that intent reuses it and the channel collapses the
 * attempts into one transaction. The reasons are separate keys because an order
 * really can owe two different refunds over its life — a cancellation refund and
 * a refund for money that arrived after the window closed are not the same
 * intent, and must not deduplicate against each other.
 */
public final class PaymentIdempotency {

    private PaymentIdempotency() {
    }

    /** The one full payment for an order. */
    public static String payment(String orderNo) {
        return orderNo + ":PAY";
    }

    /** Refunding a cancelled reservation. */
    public static String cancellationRefund(String orderNo) {
        return orderNo + ":REF:CANCEL";
    }

    /** Returning money that landed after the payment window had already closed the order. */
    public static String latePaymentRefund(String orderNo) {
        return orderNo + ":REF:LATE";
    }
}
