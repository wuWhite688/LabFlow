package com.arthur.labops.payment.channel;

import java.time.Instant;

/**
 * The payload a real gateway would POST back.
 *
 * <p>{@code idempotencyKey} identifies the channel-facing attempt when the
 * platform supplied a merchant idempotency key; channel-originated operations
 * fall back to the channel transaction id. That keeps SUCCESS redelivery
 * idempotent and, just as importantly, lets a FAILED outcome be tied to the exact
 * attempt it rejected instead of whichever attempt happens to be current later.
 */
public record ChannelCallback(
        String orderNo,
        String idempotencyKey,
        ChannelEntryType type,
        long amountCents,
        String channelTxnId,
        String status,
        Instant occurredAt) {

    static ChannelCallback of(ChannelEntry entry, String merchantIdempotencyKey) {
        return new ChannelCallback(
                entry.orderNo(),
                merchantIdempotencyKey == null ? entry.channelTxnId() : merchantIdempotencyKey,
                entry.type(),
                entry.amountCents(),
                entry.channelTxnId(),
                entry.status(),
                entry.occurredAt());
    }

    static ChannelCallback rejected(String orderNo,
                                    ChannelEntryType type,
                                    long amountCents,
                                    String merchantIdempotencyKey,
                                    String channelTxnId,
                                    Instant occurredAt) {
        return new ChannelCallback(
                orderNo,
                merchantIdempotencyKey == null ? channelTxnId : merchantIdempotencyKey,
                type,
                amountCents,
                channelTxnId,
                "FAILED",
                occurredAt);
    }
}
