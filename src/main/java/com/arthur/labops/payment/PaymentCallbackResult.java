package com.arthur.labops.payment;

/**
 * @param accepted false when the callback was a replay of one already recorded
 */
public record PaymentCallbackResult(String orderNo, boolean accepted, PaymentOrderStatus orderStatus) {
}
