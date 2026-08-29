package com.arthur.labops.payment;

/**
 * Published inside the transaction that owed the request, acted on after it
 * commits. A refund must never leave the building for a cancellation that then
 * rolled back.
 */
public record PaymentRequestQueuedEvent(String idempotencyKey) {
}
