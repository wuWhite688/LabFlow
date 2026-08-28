package com.arthur.labops.reservation.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.arthur.labops.reservation.ReservationPaymentTimeoutService;

/**
 * Single place that turns "deadline N of kind K fired" into the right state
 * transition, shared by the local scheduler, the RabbitMQ listener and the DB
 * compensation scan so all three routes cannot drift apart.
 */
@Component
public class ReservationDeadlineHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationDeadlineHandler.class);

    private final ReservationExpirationService expirationService;
    private final ReservationPaymentTimeoutService paymentTimeoutService;

    public ReservationDeadlineHandler(ReservationExpirationService expirationService,
                                      ReservationPaymentTimeoutService paymentTimeoutService) {
        this.expirationService = expirationService;
        this.paymentTimeoutService = paymentTimeoutService;
    }

    public boolean fire(ReservationDeadlineKind kind, Long reservationId) {
        boolean changed = switch (kind) {
            case APPROVAL -> expirationService.expireIfPending(reservationId);
            case PAYMENT -> paymentTimeoutService.closeIfPaymentWindowElapsed(reservationId);
        };
        log.info("Reservation deadline fired kind={} reservationId={} changed={}", kind, reservationId, changed);
        return changed;
    }
}
