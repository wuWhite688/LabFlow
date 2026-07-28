package com.arthur.labops.workorder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.UserRole;

@Service
public class WorkOrderService {

    private static final Set<WorkOrderStatus> ACTIVE_STATUSES = EnumSet.of(
            WorkOrderStatus.SUBMITTED,
            WorkOrderStatus.ASSIGNED,
            WorkOrderStatus.IN_PROGRESS,
            WorkOrderStatus.RESOLVED);

    private static final Set<ReservationStatus> OPEN_RESERVATIONS = EnumSet.of(
            ReservationStatus.PENDING,
            ReservationStatus.APPROVED);

    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            WorkOrderStatus.SUBMITTED, EnumSet.of(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.ASSIGNED, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.RESOLVED),
            WorkOrderStatus.RESOLVED, EnumSet.of(WorkOrderStatus.CLOSED, WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class),
            WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class));

    private final EquipmentRepository equipmentRepository;
    private final FaultWorkOrderRepository workOrderRepository;
    private final ReservationRepository reservationRepository;
    private final PlatformUserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final EquipmentStatusService equipmentStatusService;

    public WorkOrderService(EquipmentRepository equipmentRepository,
                            FaultWorkOrderRepository workOrderRepository,
                            ReservationRepository reservationRepository,
                            PlatformUserRepository userRepository,
                            CurrentUserService currentUserService,
                            AuditLogService auditLogService,
                            EquipmentStatusService equipmentStatusService) {
        this.equipmentRepository = equipmentRepository;
        this.workOrderRepository = workOrderRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.equipmentStatusService = equipmentStatusService;
    }

    @Transactional
    public WorkOrderResponse create(CreateWorkOrderRequest request) {
        PlatformUser reporter = currentUserService.getRequiredUser();
        Equipment equipment = equipmentRepository.findByIdForUpdate(request.equipmentId())
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        if (equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_RETIRED", "已退役设备不能创建故障工单", HttpStatus.CONFLICT);
        }
        if (workOrderRepository.existsByEquipmentIdAndStatusIn(equipment.getId(), ACTIVE_STATUSES)) {
            throw new BusinessException(
                    "ACTIVE_WORK_ORDER_EXISTS", "该设备已有未关闭的故障工单", HttpStatus.CONFLICT);
        }

        int cancelledReservations = cancelOpenReservations(equipment.getId(), reporter);
        equipment.markMaintenance();
        FaultWorkOrder workOrder = new FaultWorkOrder(
                equipment,
                reporter.getId(),
                reporter.getDisplayName(),
                request.title().trim(),
                request.description().trim(),
                request.priority());
        FaultWorkOrder saved = workOrderRepository.save(workOrder);
        String detail = "设备 " + equipment.getCode() + " 报修";
        if (cancelledReservations > 0) {
            detail += "，已取消冲突预约 " + cancelledReservations + " 条";
        }
        auditLogService.record(reporter, "WORK_ORDER_CREATED", "WORK_ORDER", saved.getId(), detail);
        return WorkOrderResponse.from(saved);
    }

    /**
     * 维修员原子接单：仅能将 SUBMITTED 工单接给自己。
     * 依赖悲观锁；并发下第二人得到 409。
     */
    @Transactional
    public WorkOrderResponse claim(Long id) {
        PlatformUser actor = currentUserService.getRequiredUser();
        if (actor.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "WORK_ORDER_CLAIM_FORBIDDEN",
                    "只有维修员可以主动接单",
                    HttpStatus.FORBIDDEN);
        }
        if (!actor.isEnabled()) {
            throw new BusinessException(
                    "ASSIGNEE_DISABLED", "当前账号已停用", HttpStatus.FORBIDDEN);
        }

        FaultWorkOrder workOrder = workOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(
                        "WORK_ORDER_NOT_FOUND", "故障工单不存在", HttpStatus.NOT_FOUND));

        if (workOrder.getStatus() != WorkOrderStatus.SUBMITTED) {
            throw new BusinessException(
                    "WORK_ORDER_ALREADY_CLAIMED",
                    "工单已被接单或状态不可接",
                    HttpStatus.CONFLICT);
        }

        workOrder.assign(actor.getId());
        auditLogService.record(actor, "WORK_ORDER_CLAIMED", "WORK_ORDER", workOrder.getId(),
                "维修员主动接单");
        return WorkOrderResponse.from(workOrder);
    }

    @Transactional
    public WorkOrderResponse transition(Long id, TransitionWorkOrderRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        FaultWorkOrder workOrder = workOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(
                        "WORK_ORDER_NOT_FOUND", "故障工单不存在", HttpStatus.NOT_FOUND));

        WorkOrderStatus current = workOrder.getStatus();
        WorkOrderStatus target = request.targetStatus();
        if (!ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new BusinessException(
                    "INVALID_WORK_ORDER_TRANSITION",
                    "工单不能从 " + current + " 流转到 " + target,
                    HttpStatus.CONFLICT);
        }

        assertTransitionAuthorized(actor, workOrder, target, request.assigneeId());

        if (target == WorkOrderStatus.ASSIGNED) {
            Long assigneeId = resolveAssigneeForAssignment(actor, request.assigneeId());
            workOrder.assign(assigneeId);
        } else {
            workOrder.transitionTo(target);
        }

        Long equipmentId = workOrder.getEquipment().getId();
        if (target == WorkOrderStatus.CLOSED || target == WorkOrderStatus.CANCELLED) {
            workOrderRepository.flush();
            equipmentStatusService.sync(equipmentId);
        }
        auditLogService.record(actor, "WORK_ORDER_" + target.name(), "WORK_ORDER", workOrder.getId(),
                "工单从 " + current + " 流转到 " + target);
        return WorkOrderResponse.from(workOrder);
    }

    @Transactional(readOnly = true)
    public Page<WorkOrderResponse> findAll(WorkOrderStatus status, WorkOrderPriority priority,
                                           Long equipmentId, Pageable pageable) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Specification<FaultWorkOrder> specification = (root, query, builder) -> builder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (priority != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("priority"), priority));
        }
        if (equipmentId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("equipment").get("id"), equipmentId));
        }
        if (actor.getRole() == UserRole.STUDENT) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("reporterId"), actor.getId()));
        } else if (actor.getRole() == UserRole.TECHNICIAN) {
            // 待接单池 + 已派给自己的工单
            specification = specification.and((root, query, builder) -> builder.or(
                    builder.equal(root.get("status"), WorkOrderStatus.SUBMITTED),
                    builder.equal(root.get("assigneeId"), actor.getId())));
        }
        return workOrderRepository.findAll(specification, pageable).map(WorkOrderResponse::from);
    }

    /**
     * 权限模型：
     * - 管理员：可派给任意维修员、取消、处理任意工单
     * - 维修员：可主动接单（claim）；派单接口只能指定自己；处理仅限自己的单；不可取消
     */
    private void assertTransitionAuthorized(PlatformUser actor, FaultWorkOrder workOrder,
                                            WorkOrderStatus target, Long requestedAssigneeId) {
        if (actor.getRole() == UserRole.ADMIN) {
            return;
        }
        if (actor.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "WORK_ORDER_FORBIDDEN", "当前角色无权操作工单", HttpStatus.FORBIDDEN);
        }
        if (target == WorkOrderStatus.CANCELLED) {
            throw new BusinessException(
                    "WORK_ORDER_CANCEL_FORBIDDEN",
                    "只有管理员可以取消工单",
                    HttpStatus.FORBIDDEN);
        }
        if (target == WorkOrderStatus.ASSIGNED) {
            // 维修员通过 status 接口只能接给自己；推荐使用 /claim
            if (workOrder.getStatus() != WorkOrderStatus.SUBMITTED) {
                throw new BusinessException(
                        "WORK_ORDER_ALREADY_CLAIMED",
                        "工单已被接单或状态不可接",
                        HttpStatus.CONFLICT);
            }
            if (requestedAssigneeId != null && !Objects.equals(requestedAssigneeId, actor.getId())) {
                throw new BusinessException(
                        "WORK_ORDER_CLAIM_SELF_ONLY",
                        "维修员只能将工单接给自己，不能指定其他维修员",
                        HttpStatus.FORBIDDEN);
            }
            return;
        }
        if (!Objects.equals(workOrder.getAssigneeId(), actor.getId())) {
            throw new BusinessException(
                    "WORK_ORDER_NOT_ASSIGNED",
                    "只能处理分配给自己的工单",
                    HttpStatus.FORBIDDEN);
        }
    }

    private Long resolveAssigneeForAssignment(PlatformUser actor, Long requestedAssigneeId) {
        if (actor.getRole() == UserRole.TECHNICIAN) {
            return actor.getId();
        }
        return requireTechnicianAssignee(requestedAssigneeId);
    }

    private Long requireTechnicianAssignee(Long assigneeId) {
        if (assigneeId == null) {
            throw new BusinessException(
                    "ASSIGNEE_REQUIRED", "派单时必须指定维修人员", HttpStatus.BAD_REQUEST);
        }
        PlatformUser assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new BusinessException(
                        "ASSIGNEE_NOT_FOUND", "指定的维修人员不存在", HttpStatus.BAD_REQUEST));
        if (assignee.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "ASSIGNEE_NOT_TECHNICIAN", "只能派给维修员角色", HttpStatus.BAD_REQUEST);
        }
        if (!assignee.isEnabled()) {
            throw new BusinessException(
                    "ASSIGNEE_DISABLED", "该维修员账号已停用", HttpStatus.BAD_REQUEST);
        }
        return assignee.getId();
    }

    private int cancelOpenReservations(Long equipmentId, PlatformUser actor) {
        List<Reservation> open = reservationRepository.findByEquipmentIdAndStatusInForUpdate(
                equipmentId, OPEN_RESERVATIONS);
        for (Reservation reservation : open) {
            reservation.cancel();
            auditLogService.record(actor, "RESERVATION_CANCELLED", "RESERVATION", reservation.getId(),
                    "设备报修联动取消预约");
        }
        return open.size();
    }
}
