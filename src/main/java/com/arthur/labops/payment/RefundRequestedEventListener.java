package com.arthur.labops.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

@Component
public class RefundRequestedEventListener {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestedEventListener.class);

    private final SimulatedPaymentChannel channel;

    public RefundRequestedEventListener(SimulatedPaymentChannel channel) {
        this.channel = channel;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundRequested(RefundRequestedEvent event) {
        try {
            channel.refund(event.orderNo(), event.amountCents());
        } catch (RuntimeException exception) {
            // The cancellation is already committed and the reservation is sitting
            // in REFUNDING. Reconciliation is what notices the money never moved;
            // failing here would only hide it behind a 500 on an unrelated request.
            log.error("Refund request to channel failed orderNo={} amountCents={}",
                    event.orderNo(), event.amountCents(), exception);
        }
    }
}
