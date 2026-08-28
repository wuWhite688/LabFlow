package com.arthur.labops.payment.channel;

/**
 * Where the simulated channel delivers its callbacks. Kept as a seam so the
 * channel never depends on the local ledger, and so a test can point deliveries
 * at a sink that fails the way a real endpoint would.
 */
public interface ChannelCallbackSink {

    void deliver(ChannelCallback callback);
}
