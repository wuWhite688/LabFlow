package com.arthur.labops.reservation.expiry;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.audit.AuditLogService;

@Service
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final AuditLogService auditLogService;

    public ReservationExpirationService(ReservationRepository reservationRepository,
                                        AuditLogService auditLogService) {
        this.reservationRepository = reservationRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public boolean expireIfPending(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        boolean expired = reservation != null && reservation.expireIfPending(Instant.now());
        if (expired) {
            auditLogService.recordSystem("RESERVATION_EXPIRED", "RESERVATION", reservationId, "待审批预约自动过期");
        }
        return expired;
    }
}

