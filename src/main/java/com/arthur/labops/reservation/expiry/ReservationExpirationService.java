package com.arthur.labops.reservation.expiry;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;

@Service
public class ReservationExpirationService {

    private final ReservationRepository reservationRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusService equipmentStatusService;
    private final AuditLogService auditLogService;

    public ReservationExpirationService(ReservationRepository reservationRepository,
                                        EquipmentRepository equipmentRepository,
                                        EquipmentStatusService equipmentStatusService,
                                        AuditLogService auditLogService) {
        this.reservationRepository = reservationRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentStatusService = equipmentStatusService;
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfPending(Long reservationId) {
        Long equipmentId = reservationRepository.findEquipmentIdById(reservationId).orElse(null);
        if (equipmentId == null) {
            return false;
        }

        equipmentRepository.findByIdForUpdate(equipmentId).orElse(null);
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        boolean expired = reservation != null && reservation.expireIfPending(Instant.now());
        if (expired) {
            auditLogService.recordSystem("RESERVATION_EXPIRED", "RESERVATION", reservationId, "待审批预约自动过期");
            equipmentStatusService.sync(equipmentId);
        }
        return expired;
    }
}
