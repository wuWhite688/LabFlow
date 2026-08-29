package com.arthur.labops.workorder;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface FaultWorkOrderRepository
        extends JpaRepository<FaultWorkOrder, Long>, JpaSpecificationExecutor<FaultWorkOrder> {

    /**
     * "Is a real fault already open on this equipment?" — the guard that stops
     * duplicate fault reports. Reconciliation tickets live in the same table but
     * must not block someone from reporting that the machine is broken.
     */
    boolean existsByEquipmentIdAndCategoryAndStatusIn(
            Long equipmentId, WorkOrderCategory category, Collection<WorkOrderStatus> statuses);

    boolean existsByDiscrepancyKey(String discrepancyKey);

    long countByCategoryAndStatusIn(WorkOrderCategory category, Collection<WorkOrderStatus> statuses);

    /**
     * Only work orders that actually hold the equipment offline may drive it to
     * MAINTENANCE. Student reports stay out of this so their limited blast
     * radius survives the next status sync.
     */
    boolean existsByEquipmentIdAndEquipmentTakenOfflineTrueAndStatusIn(
            Long equipmentId, Collection<WorkOrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workOrder from FaultWorkOrder workOrder where workOrder.id = :id")
    Optional<FaultWorkOrder> findByIdForUpdate(@Param("id") Long id);

    long countByStatusIn(Collection<WorkOrderStatus> statuses);

    long countByPriorityAndStatusIn(WorkOrderPriority priority, Collection<WorkOrderStatus> statuses);
}
