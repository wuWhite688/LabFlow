package com.arthur.labops.payment.channel;

import java.time.Instant;

/**
 * The payload a real gateway would POST back. {@code idempotencyKey} is stable
 * across redeliveries of the same channel transaction — that is the whole point
 * of it, and the reason a duplicate delivery can be swallowed downstream.
 */
public record ChannelCallback(
        String orderNo,
        String idempotencyKey,
        ChannelEntryType type,
        long amountCents,
        String channelTxnId,
        String status,
        Instant occurredAt) {

    static ChannelCallback of(ChannelEntry entry) {
        return new ChannelCallback(
                entry.orderNo(),
                entry.channelTxnId(),
                entry.type(),
                entry.amountCents(),
                entry.channelTxnId(),
                entry.status(),
                entry.occurredAt());
    }
}
