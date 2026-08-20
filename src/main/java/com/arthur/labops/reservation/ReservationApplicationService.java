package com.arthur.labops.reservation;

import org.springframework.stereotype.Service;

import com.arthur.labops.reservation.lock.ReservationLock;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;

@Service
public class ReservationApplicationService {

    private final ReservationLock reservationLock;
    private final ReservationService reservationService;
    private final CurrentUserService currentUserService;

    public ReservationApplicationService(ReservationLock reservationLock,
                                         ReservationService reservationService,
                                         CurrentUserService currentUserService) {
        this.reservationLock = reservationLock;
        this.reservationService = reservationService;
        this.currentUserService = currentUserService;
    }

    public ReservationResponse create(CreateReservationRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        return reservationLock.executeUser(actor.getId(), () ->
                reservationLock.execute(request.equipmentId(), () -> reservationService.create(request)));
    }
}
