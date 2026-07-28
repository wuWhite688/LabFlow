package com.arthur.labops.reservation.expiry;

import java.time.Instant;

public record ReservationCreatedEvent(Long reservationId, Instant expiresAt) {
}
