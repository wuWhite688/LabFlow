package com.arthur.labops.reservation;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatusService;

/**
 * Closes reservations whose short payment window elapsed and gives the calendar
 * slot back.
 *
 * <p>Mirrors {@code ReservationExpirationService}: its own {@code REQUIRES_NEW}
 * transaction per reservation, and the same Equipment &rarr; Reservation lock order
 * (extended with the payment order last) so a firing deadline can never form a
 * cycle with an approval, a cancellation or a fault report.
 */
@Service
public class ReservationPaymentTimeoutService {

    private final ReservationRepository reservationRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusService equipmentStatusService;
    private final AuditLogService auditLogService;
    private final ReservationPaymentGateway paymentGateway;

    public ReservationPaymentTimeoutService(ReservationRepository reservationRepository,
                                            EquipmentRepository equipmentRepository,
                                            EquipmentStatusService equipmentStatusService,
                                            AuditLogService auditLogService,
                                            ReservationPaymentGateway paymentGateway) {
        this.reservationRepository = reservationRepository;
        this.equipmentRepository = equipmentRepository;
        this.equipmentStatusService = equipmentStatusService;
        this.auditLogService = auditLogService;
        this.paymentGateway = paymentGateway;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean closeIfPaymentWindowElapsed(Long reservationId) {
        Long equipmentId = reservationRepository.findEquipmentIdById(reservationId).orElse(null);
        if (equipmentId == null) {
            return false;
        }

        equipmentRepository.findByIdForUpdate(equipmentId).orElse(null);
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        // The state guard lives on the entity: a deadline that fires just after the
        // payment landed finds a PAID reservation and does nothing.
        boolean closed = reservation != null && reservation.closeIfPaymentWindowElapsed(Instant.now());
        if (closed) {
            paymentGateway.closeUnpaidOrder(reservationId);
            auditLogService.recordSystem(
                    "RESERVATION_PAYMENT_EXPIRED", "RESERVATION", reservationId, "支付超时关单，时段已释放");
            equipmentStatusService.sync(equipmentId);
        }
        return closed;
    }
}
