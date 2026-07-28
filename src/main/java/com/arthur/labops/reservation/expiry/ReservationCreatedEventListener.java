package com.arthur.labops.reservation.expiry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReservationCreatedEventListener {

    private final ReservationExpiryScheduler expiryScheduler;

    public ReservationCreatedEventListener(ReservationExpiryScheduler expiryScheduler) {
        this.expiryScheduler = expiryScheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(ReservationCreatedEvent event) {
        expiryScheduler.schedule(event.reservationId(), event.expiresAt());
    }
}
