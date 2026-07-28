package com.arthur.labops.reservation.expiry;

import java.time.Instant;

public interface ReservationExpiryScheduler {

    void schedule(Long reservationId, Instant expiresAt);
}
