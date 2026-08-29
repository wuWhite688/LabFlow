package com.arthur.labops.reservation;

import java.time.Instant;

public record ReservationResponse(
        Long id,
        Long equipmentId,
        Long requesterId,
        String requesterName,
        String purpose,
        Instant startTime,
        Instant endTime,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant paymentDeadline
) {
    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEquipment().getId(),
                reservation.getRequesterId(),
                reservation.getRequesterName(),
                reservation.getPurpose(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getExpiresAt(),
                reservation.getPaymentDeadline());
    }
}
