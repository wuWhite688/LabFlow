package com.arthur.labops.reservation;

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
import com.arthur.labops.reservation.expiry.ReservationDeadlineEvent;
import com.arthur.labops.reservation.expiry.ReservationDeadlineKind;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.UserRole;

@Service
public class ReservationService {

    /** @see ReservationStatuses#QUOTA */
    private static final Set<ReservationStatus> QUOTA_STATUSES = ReservationStatuses.QUOTA;

    /** @see ReservationStatuses#OCCUPIED */
    private static final Set<ReservationStatus> OCCUPIED_STATUSES = ReservationStatuses.OCCUPIED;

    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final PlatformUserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration approvalTimeout;
    private final Duration maxDuration;
    private final Duration maxAdvance;
    private final int maxActivePerUser;
    private final Duration paymentWindow;
    private final ReservationPaymentGateway paymentGateway;
    private final ReservationClosureService closureService;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final EquipmentStatusService equipmentStatusService;

    public ReservationService(EquipmentRepository equipmentRepository,
                              ReservationRepository reservationRepository,
                              PlatformUserRepository userRepository,
                              ApplicationEventPublisher eventPublisher,
                              @Value("${labops.reservation-approval-timeout:15m}") Duration approvalTimeout,
                              @Value("${labops.reservation-max-duration:12h}") Duration maxDuration,
                              @Value("${labops.reservation-max-advance:30d}") Duration maxAdvance,
                              @Value("${labops.reservation-max-active-per-user:20}") int maxActivePerUser,
                              @Value("${labops.payment.window:10m}") Duration paymentWindow,
                              ReservationPaymentGateway paymentGateway,
                              ReservationClosureService closureService,
                              CurrentUserService currentUserService,
                              AuditLogService auditLogService,
                              EquipmentStatusService equipmentStatusService) {
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.approvalTimeout = approvalTimeout;
        this.maxDuration = maxDuration;
        this.maxAdvance = maxAdvance;
        this.maxActivePerUser = maxActivePerUser;
        this.paymentWindow = paymentWindow;
        this.paymentGateway = paymentGateway;
        this.closureService = closureService;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.equipmentStatusService = equipmentStatusService;
    }

    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        validateTimeWindow(request.startTime(), request.endTime());

        PlatformUser requester = currentUserService.getRequiredUser();
        userRepositoryForUpdate(requester.getId());
        long active = reservationRepository.countByRequesterIdAndStatusIn(
                requester.getId(), QUOTA_STATUSES);
        if (active >= maxActivePerUser) {
            throw new BusinessException(
                    "RESERVATION_QUOTA_EXCEEDED",
                    "未关闭预约数量已达上限（" + maxActivePerUser + "）",
                    HttpStatus.CONFLICT);
        }

        Equipment equipment = equipmentRepository.findByIdForUpdate(request.equipmentId())
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));

        if (equipment.getStatus() == EquipmentStatus.MAINTENANCE
                || equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_UNAVAILABLE", "设备当前不可预约", HttpStatus.CONFLICT);
        }

        assertNoConflict(equipment.getId(), request.startTime(), request.endTime(), null);
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
        eventPublisher.publishEvent(ReservationDeadlineEvent.arm(
                ReservationDeadlineKind.APPROVAL, saved.getId(), saved.getExpiresAt()));
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
            approveOrBill(reservation, equipment);
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
                "RESERVATION", reservation.getId(), decisionDetail(reservation));
        // Decided either way, so the approval deadline is spent. Drop it rather
        // than leaving it parked until its original instant.
        eventPublisher.publishEvent(ReservationDeadlineEvent.disarm(
                ReservationDeadlineKind.APPROVAL, reservation.getId(), reservation.getExpiresAt()));
        return ReservationResponse.from(reservation);
    }

    /**
     * Free equipment goes straight to APPROVED, exactly as before payment existed.
     * Priced equipment stops at AWAITING_PAYMENT: it keeps the slot, but only for
     * the payment window, and an order is opened for the money.
     */
    private void approveOrBill(Reservation reservation, Equipment equipment) {
        long amountCents = amountCentsFor(reservation, equipment);
        if (amountCents <= 0) {
            reservation.approve();
            return;
        }
        Instant now = Instant.now();
        if (!reservation.getStartTime().isAfter(now)) {
            // A priced reservation whose slot has already begun cannot be given a
            // payment window at all: any window would run into time the user is
            // supposed to already be using, and closing it would take the slot away
            // mid-session.
            throw new BusinessException(
                    "RESERVATION_START_ALREADY_PASSED",
                    "预约开始时间已过，无法进入支付流程，请重新预约",
                    HttpStatus.CONFLICT);
        }
        // Never let the window outlive the slot it is holding. A reservation
        // starting in two minutes must not get ten minutes to pay for it.
        Instant windowEnd = now.plus(paymentWindow);
        Instant deadline = windowEnd.isBefore(reservation.getStartTime())
                ? windowEnd
                : reservation.getStartTime();
        reservation.awaitPayment(deadline);
        paymentGateway.openOrder(reservation, amountCents);
        eventPublisher.publishEvent(ReservationDeadlineEvent.arm(
                ReservationDeadlineKind.PAYMENT, reservation.getId(), deadline));
    }

    /**
     * Price is per hour, charged per <em>started</em> minute and rounded up to the
     * cent so the platform never under-bills by a rounding error.
     *
     * <p>Both roundings are up, and both matter. {@code Duration.toMinutes()}
     * truncates, so billing straight off it charges 1 minute for 1m59s — and
     * charges <em>nothing</em> for anything under a minute, which then falls
     * through the {@code amount <= 0} branch and is silently treated as free
     * equipment. Reservations have no minimum length, so that was reachable.
     */
    private long amountCentsFor(Reservation reservation, Equipment equipment) {
        long hourlyPriceCents = equipment.getHourlyPriceCents();
        if (hourlyPriceCents <= 0) {
            return 0L;
        }
        long seconds = Duration.between(reservation.getStartTime(), reservation.getEndTime()).toSeconds();
        long startedMinutes = Math.ceilDiv(seconds, 60L);
        // Bounded by the max-duration rule and the price ceiling on Equipment, so
        // this cannot overflow in practice — exact rather than wrapping if it ever does.
        return Math.ceilDiv(Math.multiplyExact(hourlyPriceCents, startedMinutes), 60L);
    }

    private String decisionDetail(Reservation reservation) {
        return reservation.getStatus() == ReservationStatus.AWAITING_PAYMENT
                ? "预约审批通过，等待支付"
                : "预约审批";
    }

    @Transactional
    public ReservationResponse cancel(Long reservationId) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Reservation reservation = findForStateChange(reservationId);
        if (!reservation.getRequesterId().equals(actor.getId()) && actor.getRole() != UserRole.ADMIN) {
            throw new BusinessException(
                    "RESERVATION_NOT_OWNED", "只能取消自己的预约", HttpStatus.FORBIDDEN);
        }
        if (reservation.getStatus() == ReservationStatus.REFUNDING) {
            throw new BusinessException(
                    "RESERVATION_REFUND_IN_PROGRESS", "退款处理中，无需重复取消", HttpStatus.CONFLICT);
        }
        Long equipmentId = reservation.getEquipment().getId();
        ReservationClosure closure = closureService.close(reservation);
        if (closure == ReservationClosure.NOT_CLOSEABLE) {
            throw new BusinessException(
                    "RESERVATION_NOT_CANCELLABLE", "当前预约状态不能取消", HttpStatus.CONFLICT);
        }
        String detail = closure == ReservationClosure.REFUND_PENDING
                ? "取消已支付预约，退款处理中"
                : "取消预约";
        equipmentStatusService.sync(equipmentId);
        auditLogService.record(actor, "RESERVATION_CANCELLED", "RESERVATION", reservation.getId(), detail);
        return ReservationResponse.from(reservation);
    }

    @Transactional
    public ReservationResponse complete(Long reservationId) {
        PlatformUser actor = currentUserService.getRequiredUser();
        assertTeacherOrAdmin(actor);
        Reservation reservation = findForStateChange(reservationId);
        // APPROVED (free) and PAID (settled) are the two confirmed forms.
        if (!ReservationStatuses.CONFIRMED.contains(reservation.getStatus())) {
            throw new BusinessException(
                    "RESERVATION_NOT_COMPLETABLE", "只有已批准或已支付预约可以完成", HttpStatus.CONFLICT);
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
                    root.get("status").in(ReservationStatus.PENDING, ReservationStatus.APPROVED,
                            ReservationStatus.AWAITING_PAYMENT, ReservationStatus.PAID));
        }
        return reservationRepository.findAll(specification, pageable).map(ReservationResponse::from);
    }

    /**
     * Reservation state changes and work-order creation both touch the same
     * equipment + reservation rows. Always acquire the equipment row first,
     * then the reservation row, so concurrent approval/cancel/complete and
     * fault reporting cannot form an Equipment <-> Reservation deadlock cycle.
     */
    private void userRepositoryForUpdate(Long userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        "CURRENT_USER_NOT_FOUND", "当前登录用户不存在", HttpStatus.UNAUTHORIZED));
    }

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
        if (start.isAfter(now.plus(maxAdvance))) {
            throw new BusinessException(
                    "RESERVATION_TOO_FAR_AHEAD",
                    "预约开始时间不能超过 " + maxAdvance.toDays() + " 天后",
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
                        equipmentId, OCCUPIED_STATUSES, end, start)
                : reservationRepository.existsConflictExcludingId(
                        equipmentId, OCCUPIED_STATUSES, end, start, excludeId);
        if (conflict) {
            throw new BusinessException(
                    "RESERVATION_CONFLICT",
                    "该设备在所选时间段已有预约",
                    HttpStatus.CONFLICT);
        }
    }
}
