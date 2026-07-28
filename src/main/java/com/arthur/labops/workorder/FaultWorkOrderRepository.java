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

    boolean existsByEquipmentIdAndStatusIn(Long equipmentId, Collection<WorkOrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workOrder from FaultWorkOrder workOrder where workOrder.id = :id")
    Optional<FaultWorkOrder> findByIdForUpdate(@Param("id") Long id);

    long countByStatusIn(Collection<WorkOrderStatus> statuses);

    long countByPriorityAndStatusIn(WorkOrderPriority priority, Collection<WorkOrderStatus> statuses);
}
