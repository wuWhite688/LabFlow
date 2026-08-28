-- One row per channel transaction, enforced by the database.
--
-- The application checks for the key first, which covers the ordinary case of a
-- gateway redelivering after a missed acknowledgement. That check alone is not
-- enough: two deliveries of the same callback arriving at once both read "not
-- seen" before either writes. This index is what makes the second one lose.
create unique index uk_payment_transaction_idempotency
    on payment_transactions(idempotency_key);
