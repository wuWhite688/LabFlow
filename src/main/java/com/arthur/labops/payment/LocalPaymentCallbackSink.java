package com.arthur.labops.payment;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.arthur.labops.payment.channel.ChannelCallback;
import com.arthur.labops.payment.channel.ChannelCallbackSink;
import com.arthur.labops.payment.channel.ChannelEntryType;

/**
 * Delivers simulated-channel callbacks into the same service the HTTP endpoint
 * calls, so in-process and over-the-wire deliveries cannot diverge in how they
 * are deduplicated or applied.
 *
 * <p>The ingest is resolved lazily rather than injected. Money flows in a loop
 * here — the platform asks the channel to charge, the channel calls back into
 * the platform — and wiring that loop at construction time is a bean cycle:
 * channel → sink → ingest → callback service → dispatch → channel. Resolving the
 * callback target when a callback actually arrives matches what this really is
 * (a late-bound delivery address) and keeps the cycle out of the container,
 * rather than switching on {@code allow-circular-references} to hide it.
 */
@Component
public class LocalPaymentCallbackSink implements ChannelCallbackSink {

    private final ObjectProvider<PaymentCallbackIngest> callbackIngest;

    public LocalPaymentCallbackSink(ObjectProvider<PaymentCallbackIngest> callbackIngest) {
        this.callbackIngest = callbackIngest;
    }

    @Override
    public void deliver(ChannelCallback callback) {
        callbackIngest.getObject().ingest(new PaymentCallbackRequest(
                callback.orderNo(),
                callback.idempotencyKey(),
                callback.type() == ChannelEntryType.REFUND
                        ? PaymentTransactionType.REFUND
                        : PaymentTransactionType.PAYMENT,
                callback.amountCents(),
                callback.channelTxnId(),
                callback.status(),
                callback.occurredAt()));
    }
}
