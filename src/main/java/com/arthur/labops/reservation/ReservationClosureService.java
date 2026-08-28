package com.arthur.labops.reservation;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.arthur.labops.reservation.expiry.ReservationDeadlineEvent;
import com.arthur.labops.reservation.expiry.ReservationDeadlineKind;

/**
 * The one place a reservation is taken out of service.
 *
 * <p>There are two ways a reservation gets cancelled — the owner asking, and a
 * fault report taking the equipment offline underneath them — and once money is
 * in the link they must agree on what that means. A paid reservation cancelled
 * by a fault report has to refund exactly like one the user cancelled; keeping
 * the transition in two places is how one of them ends up quietly keeping a
 * student's money.
 *
 * <p>The caller is responsible for authorisation, audit, equipment status sync,
 * and for already holding the Equipment &rarr; Reservation locks.
 */
@Service
public class ReservationClosureService {

    private final ReservationPaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;

    public ReservationClosureService(ReservationPaymentGateway paymentGateway,
                                     ApplicationEventPublisher eventPublisher) {
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    public ReservationClosure close(Reservation reservation) {
        Instant paymentDeadline = reservation.getPaymentDeadline();
        switch (reservation.getStatus()) {
            case PENDING -> {
                reservation.cancel();
                eventPublisher.publishEvent(ReservationDeadlineEvent.disarm(
                        ReservationDeadlineKind.APPROVAL, reservation.getId(), reservation.getExpiresAt()));
                return ReservationClosure.CANCELLED;
            }
            case APPROVED -> {
                reservation.cancel();
                return ReservationClosure.CANCELLED;
            }
            case AWAITING_PAYMENT -> {
                // Nothing was collected, so this closes outright — but the payment
                // window is now dead weight and has to go with it.
                reservation.cancel();
                paymentGateway.closeUnpaidOrder(reservation.getId());
                eventPublisher.publishEvent(ReservationDeadlineEvent.disarm(
                        ReservationDeadlineKind.PAYMENT, reservation.getId(), paymentDeadline));
                return ReservationClosure.CANCELLED;
            }
            case PAID -> {
                // The slot is released now; the reservation stays open until the
                // refund callback lands, so the books never show a cancelled
                // reservation whose money is still sitting at the channel.
                reservation.beginRefund();
                paymentGateway.requestFullRefund(reservation.getId());
                return ReservationClosure.REFUND_PENDING;
            }
            default -> {
                return ReservationClosure.NOT_CLOSEABLE;
            }
        }
    }
}
