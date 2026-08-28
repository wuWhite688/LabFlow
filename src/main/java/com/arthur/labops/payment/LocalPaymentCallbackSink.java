package com.arthur.labops.payment;

import org.springframework.stereotype.Component;

import com.arthur.labops.payment.channel.ChannelCallback;
import com.arthur.labops.payment.channel.ChannelCallbackSink;
import com.arthur.labops.payment.channel.ChannelEntryType;

/**
 * Delivers simulated-channel callbacks into the same service the HTTP endpoint
 * calls, so in-process and over-the-wire deliveries cannot diverge in how they
 * are deduplicated or applied.
 */
@Component
public class LocalPaymentCallbackSink implements ChannelCallbackSink {

    private final PaymentCallbackIngest callbackIngest;

    public LocalPaymentCallbackSink(PaymentCallbackIngest callbackIngest) {
        this.callbackIngest = callbackIngest;
    }

    @Override
    public void deliver(ChannelCallback callback) {
        callbackIngest.ingest(new PaymentCallbackRequest(
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
