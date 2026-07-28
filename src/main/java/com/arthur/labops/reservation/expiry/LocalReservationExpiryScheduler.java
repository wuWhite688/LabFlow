package com.arthur.labops.reservation.expiry;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationExpiryScheduler implements ReservationExpiryScheduler {

    private final TaskScheduler taskScheduler;
    private final ReservationExpirationService expirationService;

    public LocalReservationExpiryScheduler(TaskScheduler taskScheduler,
                                           ReservationExpirationService expirationService) {
        this.taskScheduler = taskScheduler;
        this.expirationService = expirationService;
    }

    @Override
    public void schedule(Long reservationId, Instant expiresAt) {
        taskScheduler.schedule(() -> expirationService.expireIfPending(reservationId), expiresAt);
    }
}
