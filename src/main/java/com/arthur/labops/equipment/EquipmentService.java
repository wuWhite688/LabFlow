package com.arthur.labops.equipment;

import java.util.Set;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;

@Service
public class EquipmentService {

    /** Unsettled reservations block retirement — including the ones with money in flight. */
    private static final Set<ReservationStatus> OPEN_RESERVATIONS = ReservationStatuses.OPEN;

    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final EquipmentStatusService equipmentStatusService;

    public EquipmentService(EquipmentRepository equipmentRepository,
                            ReservationRepository reservationRepository,
                            CurrentUserService currentUserService,
                            AuditLogService auditLogService,
                            EquipmentStatusService equipmentStatusService) {
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.equipmentStatusService = equipmentStatusService;
    }

    @Transactional
    public EquipmentResponse create(CreateEquipmentRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        String code = request.code().trim().toUpperCase();
        if (equipmentRepository.existsByCode(code)) {
            throw new BusinessException("EQUIPMENT_CODE_EXISTS", "设备编号已存在", HttpStatus.CONFLICT);
        }
        Equipment equipment = new Equipment(
                code,
                request.name().trim(),
                request.category().trim(),
                request.location().trim(),
                normalize(request.manufacturer()),
                normalize(request.model()),
                normalize(request.responsiblePerson()),
                request.purchaseDate(),
                normalize(request.description()));
        if (request.hourlyPriceCents() != null) {
            equipment.setHourlyPriceCents(request.hourlyPriceCents());
        }
        Equipment saved = equipmentRepository.save(equipment);
        auditLogService.record(actor, "EQUIPMENT_CREATED", "EQUIPMENT", saved.getId(),
                "创建设备 " + saved.getCode());
        return EquipmentResponse.from(saved);
    }

    @Transactional
    public EquipmentResponse update(Long id, UpdateEquipmentRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Equipment equipment = equipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        if (equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException("EQUIPMENT_RETIRED", "已退役设备不能修改", HttpStatus.CONFLICT);
        }
        equipment.updateProfile(
                request.name().trim(),
                request.category().trim(),
                request.location().trim(),
                normalize(request.manufacturer()),
                normalize(request.model()),
                normalize(request.responsiblePerson()),
                request.purchaseDate(),
                normalize(request.description()));
        if (request.hourlyPriceCents() != null) {
            equipment.setHourlyPriceCents(request.hourlyPriceCents());
        }
        auditLogService.record(actor, "EQUIPMENT_UPDATED", "EQUIPMENT", equipment.getId(),
                "更新设备 " + equipment.getCode());
        return EquipmentResponse.from(equipment);
    }

    @Transactional
    public EquipmentResponse retire(Long id) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Equipment equipment = equipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        if (equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException("EQUIPMENT_ALREADY_RETIRED", "设备已退役", HttpStatus.CONFLICT);
        }
        if (equipment.getStatus() == EquipmentStatus.IN_USE) {
            throw new BusinessException("EQUIPMENT_IN_USE", "使用中的设备不能退役，请先完成预约", HttpStatus.CONFLICT);
        }
        if (equipment.getStatus() == EquipmentStatus.MAINTENANCE) {
            throw new BusinessException("EQUIPMENT_IN_MAINTENANCE", "维护中的设备不能退役，请先关闭工单", HttpStatus.CONFLICT);
        }
        List<Reservation> open = reservationRepository.findByEquipmentIdAndStatusInForUpdate(
                id, OPEN_RESERVATIONS);
        if (!open.isEmpty()) {
            throw new BusinessException(
                    "EQUIPMENT_HAS_OPEN_RESERVATIONS",
                    "存在未关闭的预约（含待支付、已支付、退款中），请先取消或完成后再退役",
                    HttpStatus.CONFLICT);
        }
        equipment.retire();
        auditLogService.record(actor, "EQUIPMENT_RETIRED", "EQUIPMENT", equipment.getId(),
                "退役设备 " + equipment.getCode());
        return EquipmentResponse.from(equipment);
    }

    @Transactional
    public EquipmentResponse restore(Long id) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Equipment equipment = equipmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        // Mirror retire()'s precondition. Equipment.restoreFromRetired throws
        // IllegalStateException, which no handler maps — it would surface as a 500
        // with an "Unhandled server exception" ERROR log for what is really a
        // client-side conflict. Every entity state guard needs a BusinessException
        // ahead of it here; the entity check stays as the second line of defence.
        if (equipment.getStatus() != EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_NOT_RETIRED", "仅已退役设备可恢复", HttpStatus.CONFLICT);
        }
        equipment.restoreFromRetired();
        equipmentStatusService.sync(equipment.getId());
        auditLogService.record(actor, "EQUIPMENT_RESTORED", "EQUIPMENT", equipment.getId(),
                "恢复设备 " + equipment.getCode());
        return EquipmentResponse.from(equipment);
    }

    @Transactional(readOnly = true)
    public Page<EquipmentResponse> findAll(EquipmentStatus status, String category,
                                           String keyword, Pageable pageable) {
        Specification<Equipment> specification = (root, query, builder) -> builder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (category != null && !category.isBlank()) {
            String normalized = category.trim().toLowerCase();
            specification = specification.and((root, query, builder) ->
                    builder.equal(builder.lower(root.get("category")), normalized));
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            specification = specification.and((root, query, builder) -> builder.or(
                    builder.like(builder.lower(root.get("code")), pattern),
                    builder.like(builder.lower(root.get("name")), pattern),
                    builder.like(builder.lower(root.get("location")), pattern)));
        }
        return equipmentRepository.findAll(specification, pageable).map(EquipmentResponse::from);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
