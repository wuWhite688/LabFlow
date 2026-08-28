package com.arthur.labops.payment.channel;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.arthur.labops.payment.channel.SimulatedChannelProperties.CallbackMode;

/**
 * In-repo stand-in for a payment gateway. It never talks to anything external:
 * it keeps its own ledger, decides when to fire callbacks, and can produce a
 * T+1 settlement file from that ledger.
 *
 * <p>Everything that would be non-deterministic in a real integration is a
 * parameter here — callback timing ({@link CallbackMode}), duplicate delivery
 * ({@link #redeliverAll()}), and lost delivery ({@link #discardPending()}) — so a
 * scenario can be replayed byte-for-byte. Channel transaction ids are a
 * monotonic sequence rather than random for the same reason.
 *
 * <p>The ledger lives in memory. That is a deliberate limit of the simulator:
 * it models "the channel remembers what we do not", which is all reconciliation
 * needs, without pretending to be a durable third party.
 */
@Component
public class SimulatedPaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentChannel.class);

    private final SimulatedChannelProperties properties;
    private final ChannelCallbackSink callbackSink;
    private final TaskScheduler taskScheduler;

    private final AtomicLong sequence = new AtomicLong();
    private final List<ChannelEntry> ledger = new ArrayList<>();
    private final Deque<ChannelCallback> pending = new ArrayDeque<>();
    private final List<ChannelCallback> delivered = new ArrayList<>();

    public SimulatedPaymentChannel(SimulatedChannelProperties properties,
                                   ChannelCallbackSink callbackSink,
                                   TaskScheduler taskScheduler) {
        this.properties = properties;
        this.callbackSink = callbackSink;
        this.taskScheduler = taskScheduler;
    }

    public ChannelEntry charge(String orderNo, long amountCents) {
        return record(orderNo, ChannelEntryType.PAYMENT, amountCents);
    }

    public ChannelEntry refund(String orderNo, long amountCents) {
        return record(orderNo, ChannelEntryType.REFUND, amountCents);
    }

    private ChannelEntry record(String orderNo, ChannelEntryType type, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("渠道交易金额必须为正数");
        }
        ChannelEntry entry;
        ChannelCallback callback;
        synchronized (this) {
            String channelTxnId = "CH-" + type.name().charAt(0) + "-" + sequence.incrementAndGet();
            entry = new ChannelEntry(
                    channelTxnId, orderNo, type, amountCents, ChannelEntry.STATUS_SUCCESS, Instant.now());
            ledger.add(entry);
            callback = ChannelCallback.of(entry);
            if (properties.getCallbackMode() != CallbackMode.IMMEDIATE) {
                pending.addLast(callback);
            }
        }
        log.info("Simulated channel recorded {} orderNo={} amountCents={} channelTxnId={}",
                type, orderNo, amountCents, entry.channelTxnId());

        switch (properties.getCallbackMode()) {
            case IMMEDIATE -> dispatch(callback);
            case DELAYED -> taskScheduler.schedule(
                    this::deliverPending, Instant.now().plus(properties.getCallbackDelay()));
            case MANUAL -> log.info("Simulated channel holding callback channelTxnId={} (MANUAL mode)",
                    entry.channelTxnId());
        }
        return entry;
    }

    /** Delivers every queued callback. No-op in {@link CallbackMode#IMMEDIATE}. */
    public int deliverPending() {
        List<ChannelCallback> batch = drainPending();
        batch.forEach(this::dispatch);
        return batch.size();
    }

    /**
     * Replays every callback the channel has already delivered. This is what a
     * real gateway does when it does not see a timely acknowledgement, and it is
     * the reason the local side needs an idempotency key rather than good luck.
     */
    public int redeliverAll() {
        List<ChannelCallback> replay;
        synchronized (this) {
            replay = new ArrayList<>(delivered);
        }
        replay.forEach(this::dispatch);
        return replay.size();
    }

    /**
     * Throws away callbacks the channel has queued but not delivered, modelling a
     * delivery that never lands (or a local write that failed and was never
     * retried). The channel ledger keeps the transaction — which is exactly the
     * asymmetry reconciliation exists to catch.
     */
    public int discardPending() {
        List<ChannelCallback> dropped = drainPending();
        if (!dropped.isEmpty()) {
            log.warn("Simulated channel discarded {} undelivered callback(s); channel ledger keeps them",
                    dropped.size());
        }
        return dropped.size();
    }

    private synchronized List<ChannelCallback> drainPending() {
        List<ChannelCallback> batch = new ArrayList<>(pending);
        pending.clear();
        return batch;
    }

    private void dispatch(ChannelCallback callback) {
        synchronized (this) {
            delivered.add(callback);
        }
        try {
            callbackSink.deliver(callback);
        } catch (RuntimeException exception) {
            // A real gateway does not roll its own books back because our endpoint
            // blew up. Keep the ledger entry and let reconciliation find the gap.
            log.warn("Simulated channel callback delivery failed channelTxnId={} orderNo={}",
                    callback.channelTxnId(), callback.orderNo(), exception);
        }
    }

    public synchronized List<ChannelEntry> ledger() {
        return List.copyOf(ledger);
    }

    public synchronized List<ChannelEntry> entriesFor(LocalDate settlementDate) {
        Instant from = settlementDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = settlementDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return ledger.stream()
                .filter(entry -> !entry.occurredAt().isBefore(from) && entry.occurredAt().isBefore(to))
                .toList();
    }

    /**
     * Writes the T+1 settlement file for {@code settlementDate}. Day boundaries are
     * UTC so a bill is reproducible regardless of where the process runs.
     */
    public Path writeDailyBill(LocalDate settlementDate) {
        Path target = billPath(settlementDate);
        ChannelBillFile.write(target, entriesFor(settlementDate));
        log.info("Simulated channel wrote settlement file date={} path={}", settlementDate, target);
        return target;
    }

    public Path billPath(LocalDate settlementDate) {
        return Path.of(properties.getBillDirectory(), "channel-bill-" + settlementDate + ".csv");
    }

    /**
     * Test seam: forget this channel's books. Never called by application code.
     *
     * <p>The transaction sequence deliberately keeps counting. A real gateway
     * never reissues a transaction id, and rewinding it here would hand a fresh
     * scenario an id the local ledger already holds from an earlier one — which
     * the idempotency check would then correctly, and very confusingly, treat as
     * a replay.
     */
    public synchronized void reset() {
        ledger.clear();
        pending.clear();
        delivered.clear();
    }
}
