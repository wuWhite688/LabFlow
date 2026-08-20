package com.arthur.labops.reservation;

import java.util.EnumSet;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
import com.arthur.labops.reservation.expiry.ReservationCreatedEvent;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.UserRole;

@Service
public class ReservationService {

    private static final Set<ReservationStatus> CONFLICTING_STATUSES =
            EnumSet.of(ReservationStatus.PENDING, ReservationStatus.APPROVED);

    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration approvalTimeout;
    private final Duration maxDuration;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final EquipmentStatusService equipmentStatusService;

    public ReservationService(EquipmentRepository equipmentRepository,
                              ReservationRepository reservationRepository,
                              ApplicationEventPublisher eventPublisher,
                              @Value("${labops.reservation-approval-timeout:15m}") Duration approvalTimeout,
                              @Value("${labops.reservation-max-duration:12h}") Duration maxDuration,
                              CurrentUserService currentUserService,
                              AuditLogService auditLogService,
                              EquipmentStatusService equipmentStatusService) {
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.approvalTimeout = approvalTimeout;
        this.maxDuration = maxDuration;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.equipmentStatusService = equipmentStatusService;
    }

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        validateTimeWindow(request.startTime(), request.endTime());

        Equipment equipment = equipmentRepository.findByIdForUpdate(request.equipmentId())
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));

        if (equipment.getStatus() == EquipmentStatus.MAINTENANCE
                || equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_UNAVAILABLE", "设备当前不可预约", HttpStatus.CONFLICT);
        }

        assertNoConflict(equipment.getId(), request.startTime(), request.endTime(), null);

        PlatformUser requester = currentUserService.getRequiredUser();
        Instant expiresAt = Instant.now().plus(approvalTimeout);
        Reservation reservation = new Reservation(
                equipment,
                requester.getId(),
                requester.getDisplayName(),
                request.purpose().trim(),
                request.startTime(),
                request.endTime(),
                expiresAt);
        Reservation saved = reservationRepository.save(reservation);
        auditLogService.record(requester, "RESERVATION_CREATED", "RESERVATION", saved.getId(),
                "预约设备 " + equipment.getCode());
        eventPublisher.publishEvent(new ReservationCreatedEvent(saved.getId(), saved.getExpiresAt()));
        return ReservationResponse.from(saved);
    }

    @Transactional(noRollbackFor = ReservationAlreadyExpiredException.class)
    public ReservationResponse decide(Long reservationId, ReservationDecisionRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Reservation reservation = findForStateChange(reservationId);
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new BusinessException(
                    "RESERVATION_NOT_PENDING", "只有待审批预约可以处理", HttpStatus.CONFLICT);
        }
        if (reservation.expireIfPending(Instant.now())) {
            auditLogService.recordSystem(
                    "RESERVATION_EXPIRED", "RESERVATION", reservation.getId(), "审批时发现待审批预约已过期");
            throw new ReservationAlreadyExpiredException();
        }
        assertTeacherOrAdmin(actor);
        Equipment equipment = reservation.getEquipment();
        if (request.decision() == ReservationStatus.APPROVED) {
            if (equipment.getStatus() == EquipmentStatus.MAINTENANCE
                    || equipment.getStatus() == EquipmentStatus.RETIRED) {
                throw new BusinessException(
                        "EQUIPMENT_UNAVAILABLE", "设备当前不可预约", HttpStatus.CONFLICT);
            }
            assertNoConflict(
                    equipment.getId(),
                    reservation.getStartTime(),
                    reservation.getEndTime(),
                    reservation.getId());
            reservation.approve();
            equipmentStatusService.sync(equipment.getId());
        } else if (request.decision() == ReservationStatus.REJECTED) {
            reservation.reject();
        } else {
            throw new BusinessException(
                    "INVALID_RESERVATION_DECISION",
                    "审批结果只能是 APPROVED 或 REJECTED",
                    HttpStatus.BAD_REQUEST);
        }
        auditLogService.record(actor, "RESERVATION_" + request.decision().name(),
                "RESERVATION", reservation.getId(), "预约审批");
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse cancel(Long reservationId) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Reservation reservation = findForStateChange(reservationId);
        if (!reservation.getRequesterId().equals(actor.getId()) && actor.getRole() != UserRole.ADMIN) {
            throw new BusinessException(
                    "RESERVATION_NOT_OWNED", "只能取消自己的预约", HttpStatus.FORBIDDEN);
        }
        if (reservation.getStatus() != ReservationStatus.PENDING
                && reservation.getStatus() != ReservationStatus.APPROVED) {
            throw new BusinessException(
                    "RESERVATION_NOT_CANCELLABLE", "当前预约状态不能取消", HttpStatus.CONFLICT);
        }
        Long equipmentId = reservation.getEquipment().getId();
        reservation.cancel();
        equipmentStatusService.sync(equipmentId);
        auditLogService.record(actor, "RESERVATION_CANCELLED", "RESERVATION", reservation.getId(), "取消预约");
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse complete(Long reservationId) {
        PlatformUser actor = currentUserService.getRequiredUser();
        assertTeacherOrAdmin(actor);
        Reservation reservation = findForStateChange(reservationId);
        if (reservation.getStatus() != ReservationStatus.APPROVED) {
            throw new BusinessException(
                    "RESERVATION_NOT_COMPLETABLE", "只有已批准预约可以完成", HttpStatus.CONFLICT);
        }
        Long equipmentId = reservation.getEquipment().getId();
        reservation.complete();
        equipmentStatusService.sync(equipmentId);
        auditLogService.record(actor, "RESERVATION_COMPLETED", "RESERVATION", reservation.getId(), "确认设备使用完成");
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public Page<ReservationResponse> findAll(ReservationStatus status, Long equipmentId, Pageable pageable) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Specification<Reservation> specification = (root, query, builder) -> builder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (equipmentId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("equipment").get("id"), equipmentId));
        }
        if (actor.getRole() == UserRole.STUDENT) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("requesterId"), actor.getId()));
        } else if (actor.getRole() == UserRole.TECHNICIAN) {
            // 维修员侧重点是设备可用性，只看已批准/进行相关状态的排期
            specification = specification.and((root, query, builder) ->
                    root.get("status").in(ReservationStatus.PENDING, ReservationStatus.APPROVED));
        }
        return reservationRepository.findAll(specification, pageable).map(ReservationResponse::from);
    }

    /**
     * Reservation state changes and work-order creation both touch the same
     * equipment + reservation rows. Always acquire the equipment row first,
     * then the reservation row, so concurrent approval/cancel/complete and
     * fault reporting cannot form an Equipment <-> Reservation deadlock cycle.
     */
    private Reservation findForStateChange(Long reservationId) {
        Long equipmentId = reservationRepository.findEquipmentIdById(reservationId)
                .orElseThrow(() -> new BusinessException(
                        "RESERVATION_NOT_FOUND", "预约不存在", HttpStatus.NOT_FOUND));

        equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));

        return reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BusinessException(
                        "RESERVATION_NOT_FOUND", "预约不存在", HttpStatus.NOT_FOUND));
    }

    private void assertTeacherOrAdmin(PlatformUser actor) {
        if (actor.getRole() != UserRole.TEACHER && actor.getRole() != UserRole.ADMIN) {
            throw new BusinessException(
                    "RESERVATION_DECISION_FORBIDDEN", "当前角色无权审批或完成预约", HttpStatus.FORBIDDEN);
        }
    }

    private void validateTimeWindow(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw new BusinessException(
                    "INVALID_RESERVATION_TIME",
                    "预约开始时间必须早于结束时间",
                    HttpStatus.BAD_REQUEST);
        }
        Instant now = Instant.now();
        if (!start.isAfter(now)) {
            throw new BusinessException(
                    "RESERVATION_START_IN_PAST",
                    "预约开始时间必须晚于当前时间",
                    HttpStatus.BAD_REQUEST);
        }
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(maxDuration) > 0) {
            throw new BusinessException(
                    "RESERVATION_DURATION_TOO_LONG",
                    "单次预约时长不能超过 " + maxDuration.toHours() + " 小时",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void assertNoConflict(Long equipmentId, Instant start, Instant end, Long excludeId) {
        boolean conflict = excludeId == null
                ? reservationRepository.existsByEquipmentIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
                        equipmentId, CONFLICTING_STATUSES, end, start)
                : reservationRepository.existsConflictExcludingId(
                        equipmentId, CONFLICTING_STATUSES, end, start, excludeId);
        if (conflict) {
            throw new BusinessException(
                    "RESERVATION_CONFLICT",
                    "该设备在所选时间段已有预约",
                    HttpStatus.CONFLICT);
        }
    }
}
