package com.arthur.labops.payment;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Field lengths mirror the ledger's column widths on purpose. Without them an
 * over-long value reaches the database and comes back as a constraint violation
 * that looks exactly like a duplicate-key collision — which is the sort of
 * ambiguity that gets a real payment quietly reported as "already recorded".
 */
public record PaymentCallbackRequest(
        @NotBlank @Size(max = 64) String orderNo,
        @NotBlank @Size(max = 120) String idempotencyKey,
        @NotNull PaymentTransactionType type,
        @NotNull @Positive Long amountCents,
        @NotBlank @Size(max = 80) String channelTxnId,
        @NotBlank @Size(max = 30) String status,
        @NotNull Instant occurredAt) {
}
