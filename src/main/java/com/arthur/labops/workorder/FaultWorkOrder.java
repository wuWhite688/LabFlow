package com.arthur.labops.workorder;

import java.time.Instant;

import com.arthur.labops.equipment.Equipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "fault_work_orders")
public class FaultWorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipment_id", nullable = false)
    private Equipment equipment;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reporter_name", nullable = false, length = 80)
    private String reporterName;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkOrderPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkOrderStatus status;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Whether this work order requires the equipment to stay offline.
     *
     * <p>Student reports intentionally leave the equipment usable (only the
     * reporter's own reservations are cancelled), so they must not drive
     * {@link com.arthur.labops.equipment.EquipmentStatusService} to MAINTENANCE.
     * Privileged reports and any assignment set this flag explicitly.
     */
    @Column(name = "equipment_taken_offline", nullable = false)
    private boolean equipmentTakenOffline;

    /**
     * What kind of problem this ticket represents. Reconciliation reuses this
     * table, and everything that predates it is a real equipment fault.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkOrderCategory category = WorkOrderCategory.FAULT;

    /**
     * Stable identity of the reconciliation discrepancy this ticket was raised
     * for, null for fault reports. Carries a unique index: re-running
     * reconciliation for the same day must not pile up duplicates, and the index
     * is what enforces that rather than a read-then-write check.
     */
    @Column(name = "discrepancy_key", length = 160)
    private String discrepancyKey;

    @Version
    private long version;

    protected FaultWorkOrder() {
    }

    /**
     * Reconciliation ticket. Never takes the equipment offline: a books problem
     * says nothing about whether the microscope works.
     */
    public static FaultWorkOrder discrepancy(Equipment equipment, Long systemReporterId, String discrepancyKey,
                                             String title, String description, WorkOrderPriority priority) {
        FaultWorkOrder workOrder = new FaultWorkOrder(
                equipment, systemReporterId, SYSTEM_REPORTER_NAME, title, description, priority);
        workOrder.category = WorkOrderCategory.PAYMENT_DISCREPANCY;
        workOrder.discrepancyKey = discrepancyKey;
        return workOrder;
    }

    /** Display name for tickets the platform raises for itself. */
    public static final String SYSTEM_REPORTER_NAME = "对账任务";

    public FaultWorkOrder(Equipment equipment, Long reporterId, String reporterName,
                          String title, String description, WorkOrderPriority priority) {
        this.equipment = equipment;
        this.reporterId = reporterId;
        this.reporterName = reporterName;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.status = WorkOrderStatus.SUBMITTED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void assign(Long assigneeId) {
        this.assigneeId = assigneeId;
        this.status = WorkOrderStatus.ASSIGNED;
        this.updatedAt = Instant.now();
    }

    /** Marks that the equipment is held offline for this work order. */
    public void markEquipmentTakenOffline() {
        this.equipmentTakenOffline = true;
        this.updatedAt = Instant.now();
    }

    public void transitionTo(WorkOrderStatus targetStatus) {
        this.status = targetStatus;
        this.updatedAt = Instant.now();
        if (targetStatus == WorkOrderStatus.RESOLVED) {
            this.resolvedAt = this.updatedAt;
        } else if (targetStatus == WorkOrderStatus.IN_PROGRESS) {
            this.resolvedAt = null;
        }
    }

    public Long getId() { return id; }
    public Equipment getEquipment() { return equipment; }
    public Long getReporterId() { return reporterId; }
    public String getReporterName() { return reporterName; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public WorkOrderPriority getPriority() { return priority; }
    public WorkOrderStatus getStatus() { return status; }
    public Long getAssigneeId() { return assigneeId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public boolean isEquipmentTakenOffline() { return equipmentTakenOffline; }
    public WorkOrderCategory getCategory() { return category; }
    public String getDiscrepancyKey() { return discrepancyKey; }
}
