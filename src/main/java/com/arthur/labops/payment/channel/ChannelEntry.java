package com.arthur.labops.payment.channel;

import java.time.Instant;

/**
 * One line of the channel's own books. This is what the T+1 bill is generated
 * from, and it exists independently of whether the local side ever managed to
 * record the matching callback.
 */
public record ChannelEntry(
        String channelTxnId,
        String orderNo,
        ChannelEntryType type,
        long amountCents,
        String status,
        Instant occurredAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
}
