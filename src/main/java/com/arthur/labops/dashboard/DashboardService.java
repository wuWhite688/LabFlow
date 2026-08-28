package com.arthur.labops.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.SystemAccountInitializer;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;

@Service
public class DashboardService {

    private final PlatformUserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final FaultWorkOrderRepository workOrderRepository;

    public DashboardService(PlatformUserRepository userRepository,
                            EquipmentRepository equipmentRepository,
                            ReservationRepository reservationRepository,
                            FaultWorkOrderRepository workOrderRepository) {
        this.userRepository = userRepository;
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.workOrderRepository = workOrderRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStatsResponse getStats() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        EnumSet<WorkOrderStatus> openStatuses = EnumSet.of(
                WorkOrderStatus.SUBMITTED, WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.RESOLVED);
        return new DashboardStatsResponse(
                // Excludes the platform's own account: it is a foreign-key target for
                // records the platform raises for itself, not a user of the platform.
                userRepository.countByUsernameNot(SystemAccountInitializer.SYSTEM_USERNAME),
                equipmentRepository.count(),
                equipmentRepository.countByStatus(EquipmentStatus.AVAILABLE),
                equipmentRepository.countByStatus(EquipmentStatus.MAINTENANCE),
                reservationRepository.countByStatus(ReservationStatus.PENDING),
                reservationRepository.countByStatus(ReservationStatus.APPROVED),
                reservationRepository.countByStartTimeBetween(dayStart, dayEnd),
                workOrderRepository.countByStatusIn(openStatuses),
                workOrderRepository.countByPriorityAndStatusIn(WorkOrderPriority.URGENT, openStatuses));
    }
}
