package com.arthur.labops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentOrderLatePaymentTest {

    @Test
    void closedUnpaidOrderAcceptsOnlyTheBilledAmount() {
        PaymentOrder order = new PaymentOrder("LF00000001", 1L, 2L, 3L, 6_000L);
        order.close();

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
        assertThat(order.acceptsLatePayment(6_000L)).isTrue();
        assertThat(order.acceptsLatePayment(1L)).isFalse();
        assertThat(order.acceptsLatePayment(12_000L)).isFalse();
        assertThat(order.acceptsPayment(6_000L))
                .as("ordinary payment coherence still matches outstanding, but late path must not skip the check")
                .isTrue();
    }

    @Test
    void applyLatePaymentRejectsAMismatchedAmount() {
        PaymentOrder order = new PaymentOrder("LF00000002", 1L, 2L, 3L, 6_000L);
        order.close();

        assertThatThrownBy(() -> order.applyLatePayment(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("6000");
        assertThat(order.getPaidCents()).isZero();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    @Test
    void applyLatePaymentRecordsTheBilledAmountAsRefundDue() {
        PaymentOrder order = new PaymentOrder("LF00000003", 1L, 2L, 3L, 6_000L);
        order.close();
        order.applyLatePayment(6_000L);

        assertThat(order.getPaidCents()).isEqualTo(6_000L);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUND_DUE);
        assertThat(order.acceptsLatePayment(6_000L)).isFalse();
    }
}
