package com.arthur.labops.reservation.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReservationDeadlineEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationDeadlineEventListener.class);

    private final ReservationExpiryScheduler expiryScheduler;

    public ReservationDeadlineEventListener(ReservationExpiryScheduler expiryScheduler) {
        this.expiryScheduler = expiryScheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeadlineEvent(ReservationDeadlineEvent event) {
        try {
            switch (event.action()) {
                case ARM -> expiryScheduler.schedule(event.kind(), event.reservationId(), event.deadline());
                case DISARM -> expiryScheduler.cancel(event.kind(), event.reservationId(), event.deadline());
            }
        } catch (RuntimeException exception) {
            // The transaction has already committed. Deadline bookkeeping is a
            // best-effort optimisation on top of the DB compensation scan, so a
            // broker failure here must not surface as a failed business call.
            log.warn("Reservation deadline {} failed kind={} reservationId={}",
                    event.action(), event.kind(), event.reservationId(), exception);
        }
    }
}
