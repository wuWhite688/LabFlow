package com.arthur.labops.reservation;

import org.springframework.stereotype.Service;

import com.arthur.labops.reservation.lock.ReservationLock;

@Service
public class ReservationApplicationService {

    private final ReservationLock reservationLock;
    private final ReservationService reservationService;

    public ReservationApplicationService(ReservationLock reservationLock,
                                         ReservationService reservationService) {
        this.reservationLock = reservationLock;
        this.reservationService = reservationService;
    }

    public ReservationResponse create(CreateReservationRequest request) {
        return reservationLock.execute(
                request.equipmentId(),
                () -> reservationService.create(request));
    }
}
