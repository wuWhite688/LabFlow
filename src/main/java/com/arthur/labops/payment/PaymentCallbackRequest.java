package com.arthur.labops.payment;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentCallbackRequest(
        @NotBlank String orderNo,
        @NotBlank String idempotencyKey,
        @NotNull PaymentTransactionType type,
        @NotNull @Positive Long amountCents,
        @NotBlank String channelTxnId,
        @NotBlank String status,
        @NotNull Instant occurredAt) {
}
