package com.arthur.labops.payment;

public record PaymentOrderResponse(
        String orderNo,
        Long reservationId,
        long amountCents,
        long paidCents,
        long refundedCents,
        PaymentOrderStatus status) {

    static PaymentOrderResponse from(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getOrderNo(),
                order.getReservationId(),
                order.getAmountCents(),
                order.getPaidCents(),
                order.getRefundedCents(),
                order.getStatus());
    }
}
