package com.arthur.labops.equipment;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderStatus;

/**
 * 按业务优先级同步设备状态：
 * RETIRED &gt; MAINTENANCE（存在未关闭工单）&gt; IN_USE（当前时段有已批准预约）&gt; AVAILABLE
 */
@Service
public class EquipmentStatusService {

    private static final Set<WorkOrderStatus> ACTIVE_WORK_ORDERS = EnumSet.of(
            WorkOrderStatus.SUBMITTED,
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.RESOLVED);

    private final EquipmentRepository equipmentRepository;
    private final FaultWorkOrderRepository workOrderRepository;
    private final ReservationRepository reservationRepository;

    public EquipmentStatusService(EquipmentRepository equipmentRepository,
                                  FaultWorkOrderRepository workOrderRepository,
                                  ReservationRepository reservationRepository) {
        this.equipmentRepository = equipmentRepository;
        this.workOrderRepository = workOrderRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional
    public void sync(Long equipmentId) {
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId).orElse(null);
        if (equipment == null || equipment.getStatus() == EquipmentStatus.RETIRED) {
            return;
        }

        Instant now = Instant.now();
        // Only work orders holding the equipment offline count. A student report
        // leaves the equipment usable by design; deriving MAINTENANCE from every
        // active work order would undo that on the next scheduled sync.
        if (workOrderRepository.existsByEquipmentIdAndEquipmentTakenOfflineTrueAndStatusIn(
                equipmentId, ACTIVE_WORK_ORDERS)) {
            equipment.forceStatus(EquipmentStatus.MAINTENANCE);
            return;
        }
        // APPROVED (free) and PAID (settled) are the two confirmed forms of the same
        // thing. AWAITING_PAYMENT holds the slot but must not read as IN_USE — nobody
        // is using equipment that has not been paid for yet, and letting it do so
        // would block retirement and fault reporting on an order that may time out.
        if (reservationRepository.existsByEquipmentIdAndStatusInAndStartTimeLessThanEqualAndEndTimeGreaterThan(
                equipmentId, ReservationStatuses.CONFIRMED, now, now)) {
            equipment.forceStatus(EquipmentStatus.IN_USE);
            return;
        }
        equipment.forceStatus(EquipmentStatus.AVAILABLE);
    }
}
