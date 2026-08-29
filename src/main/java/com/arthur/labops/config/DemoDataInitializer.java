package com.arthur.labops.config;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.audit.OperationLog;
import com.arthur.labops.audit.OperationLogRepository;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;

@Component
@Order(2)
@ConditionalOnProperty(name = "labops.demo-data.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final FaultWorkOrderRepository workOrderRepository;
    private final PlatformUserRepository userRepository;
    private final OperationLogRepository operationLogRepository;
    private final JdbcTemplate jdbcTemplate;

    public DemoDataInitializer(EquipmentRepository equipmentRepository,
                               ReservationRepository reservationRepository,
                               FaultWorkOrderRepository workOrderRepository,
                               PlatformUserRepository userRepository,
                               OperationLogRepository operationLogRepository,
                               JdbcTemplate jdbcTemplate) {
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.workOrderRepository = workOrderRepository;
        this.userRepository = userRepository;
        this.operationLogRepository = operationLogRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (equipmentRepository.count() > 0) return;

        PlatformUser student = requiredUser("student");
        PlatformUser teacher = requiredUser("teacher");
        PlatformUser technician = requiredUser("technician");
        PlatformUser technician2 = requiredUser("technician2");
        PlatformUser admin = requiredUser("admin");

        List<Equipment> equipment = equipmentRepository.saveAll(List.of(
                equipment("SEM-201", "场发射扫描电子显微镜", "显微成像", "材料楼 A208", "ZEISS", "GeminiSEM 300", "周教授", LocalDate.of(2022, 9, 15), "用于材料表面形貌与微区成分分析"),
                equipment("HPLC-105", "高效液相色谱仪", "分析检测", "化学楼 B315", "Agilent", "1260 Infinity II", "许老师", LocalDate.of(2023, 3, 8), "支持药物与有机样品的定性定量分析"),
                equipment("XRD-302", "X 射线衍射仪", "结构分析", "材料楼 A112", "Rigaku", "SmartLab SE", "周教授", LocalDate.of(2021, 11, 20), "用于晶体结构、物相与残余应力分析"),
                equipment("PCR-018", "实时荧光定量 PCR 仪", "生命科学", "生科楼 C406", "Bio-Rad", "CFX96 Touch", "梁老师", LocalDate.of(2023, 6, 12), "支持基因表达与核酸定量实验"),
                equipment("UV-077", "紫外可见分光光度计", "光谱分析", "化学楼 B218", "Shimadzu", "UV-2600i", "许老师", LocalDate.of(2020, 4, 26), "覆盖常规吸光度、动力学与光谱扫描"),
                equipment("CENT-041", "高速冷冻离心机", "样品制备", "生科楼 C213", "Eppendorf", "Centrifuge 5910 Ri", "梁老师", LocalDate.of(2022, 1, 18), "适用于细胞、蛋白及核酸样品分离"),
                equipment("FTIR-126", "傅里叶变换红外光谱仪", "光谱分析", "材料楼 A305", "Thermo Fisher", "Nicolet iS20", "周教授", LocalDate.of(2021, 7, 9), "用于官能团鉴定与材料结构分析"),
                equipment("AUTO-009", "全自动生化分析仪", "生命科学", "生科楼 C118", "Mindray", "BS-430", "梁老师", LocalDate.of(2024, 2, 21), "面向批量生化指标检测与教学实验")
        ));

        // Two priced seeds so the demo has both branches of approval: the free
        // equipment goes straight to APPROVED, the priced ones stop at
        // AWAITING_PAYMENT with an order open.
        equipment.get(0).setHourlyPriceCents(12_000L);
        equipment.get(2).setHourlyPriceCents(8_000L);

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        // 按本地时区生成自然错开的整点/半点
        Instant res1Start = today.plusDays(1).atTime(9, 0).atZone(zone).toInstant();
        Instant res1End = res1Start.plus(3, ChronoUnit.HOURS);
        Instant res2Start = today.plusDays(1).atTime(10, 30).atZone(zone).toInstant();
        Instant res2End = res2Start.plus(2, ChronoUnit.HOURS);
        Instant res3Start = today.plusDays(2).atTime(14, 0).atZone(zone).toInstant();
        Instant res3End = res3Start.plus(4, ChronoUnit.HOURS);
        Instant res4Start = today.minusDays(1).atTime(15, 30).atZone(zone).toInstant();
        Instant res4End = res4Start.plus(2, ChronoUnit.HOURS);

        Instant now = Instant.now();
        Reservation pending = new Reservation(equipment.get(0), student.getId(), student.getDisplayName(),
                "纳米复合材料断面形貌观察", res1Start, res1End, now.plus(Duration.ofDays(1)));
        Reservation approved = new Reservation(equipment.get(1), student.getId(), student.getDisplayName(),
                "咖啡因标准曲线及未知样品测定", res2Start, res2End, now.plus(Duration.ofDays(1)));
        approved.approve();
        Reservation teacherReservation = new Reservation(equipment.get(6), teacher.getId(), teacher.getDisplayName(),
                "高分子薄膜老化前后官能团对比", res3Start, res3End, now.plus(Duration.ofDays(1)));
        teacherReservation.approve();
        Reservation completed = new Reservation(equipment.get(5), student.getId(), student.getDisplayName(),
                "细胞裂解液梯度离心", res4Start, res4End, now.plus(Duration.ofDays(1)));
        completed.approve();
        completed.complete();
        reservationRepository.saveAll(List.of(pending, approved, teacherReservation, completed));

        // These three seeds represent equipment already taken offline for repair, so
        // each work order must hold that state — otherwise EquipmentStatusService.sync
        // brings the equipment back to AVAILABLE on the next scheduled scan.
        equipment.get(2).markMaintenance();
        FaultWorkOrder submitted = new FaultWorkOrder(equipment.get(2), student.getId(), student.getDisplayName(),
                "样品台回零异常", "启动自检时样品台在 2θ 位置停顿并提示限位错误，已重新上电一次。", WorkOrderPriority.URGENT);
        submitted.markEquipmentTakenOffline();

        equipment.get(3).markMaintenance();
        FaultWorkOrder inProgress = new FaultWorkOrder(equipment.get(3), teacher.getId(), teacher.getDisplayName(),
                "热盖温度波动", "连续运行 40 分钟后热盖温度上下波动约 3℃，可能影响扩增曲线稳定性。", WorkOrderPriority.HIGH);
        inProgress.assign(technician.getId());
        inProgress.transitionTo(WorkOrderStatus.IN_PROGRESS);
        inProgress.markEquipmentTakenOffline();

        equipment.get(4).markMaintenance();
        FaultWorkOrder resolved = new FaultWorkOrder(equipment.get(4), student.getId(), student.getDisplayName(),
                "氘灯使用寿命告警", "系统提示氘灯累计时长达到维护阈值，基线噪声略有升高。", WorkOrderPriority.MEDIUM);
        resolved.assign(technician2.getId());
        resolved.transitionTo(WorkOrderStatus.IN_PROGRESS);
        resolved.transitionTo(WorkOrderStatus.RESOLVED);
        resolved.markEquipmentTakenOffline();
        workOrderRepository.saveAllAndFlush(List.of(submitted, inProgress, resolved));

        updateWorkOrderTimes(
                submitted.getId(),
                today.minusDays(1).atTime(9, 30).atZone(zone).toInstant(),
                today.minusDays(1).atTime(9, 30).atZone(zone).toInstant(),
                null);
        updateWorkOrderTimes(
                inProgress.getId(),
                today.minusDays(2).atTime(14, 0).atZone(zone).toInstant(),
                today.minusDays(1).atTime(10, 30).atZone(zone).toInstant(),
                null);
        updateWorkOrderTimes(
                resolved.getId(),
                today.minusDays(3).atTime(9, 0).atZone(zone).toInstant(),
                today.minusDays(1).atTime(16, 0).atZone(zone).toInstant(),
                today.minusDays(1).atTime(16, 0).atZone(zone).toInstant());

        OperationLog log1 = new OperationLog(admin, "EQUIPMENT_CREATED", "EQUIPMENT", equipment.get(0).getId(), "批量导入实验室设备台账");
        OperationLog log2 = new OperationLog(student, "RESERVATION_CREATED", "RESERVATION", pending.getId(), "提交扫描电镜预约");
        OperationLog log3 = new OperationLog(teacher, "RESERVATION_APPROVED", "RESERVATION", approved.getId(), "批准液相色谱预约");
        OperationLog log4 = new OperationLog(technician, "WORK_ORDER_IN_PROGRESS", "WORK_ORDER", inProgress.getId(), "开始处理 PCR 仪温控故障");
        operationLogRepository.saveAllAndFlush(List.of(log1, log2, log3, log4));
        updateOperationLogTime(log1.getId(), today.minusDays(2).atTime(9, 0).atZone(zone).toInstant());
        updateOperationLogTime(log2.getId(), today.minusDays(1).atTime(9, 30).atZone(zone).toInstant());
        updateOperationLogTime(log3.getId(), today.minusDays(1).atTime(11, 0).atZone(zone).toInstant());
        updateOperationLogTime(log4.getId(), today.minusDays(1).atTime(14, 30).atZone(zone).toInstant());
    }

    private Equipment equipment(String code, String name, String category, String location,
                                String manufacturer, String model, String responsiblePerson,
                                LocalDate purchaseDate, String description) {
        return new Equipment(code, name, category, location, manufacturer, model,
                responsiblePerson, purchaseDate, description);
    }

    private PlatformUser requiredUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Missing demo user: " + username));
    }

    private void updateWorkOrderTimes(Long id, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
        int updated = jdbcTemplate.update(
                "update fault_work_orders set created_at = ?, updated_at = ?, resolved_at = ? where id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(updatedAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt),
                id);
        requireSingleSeedUpdate("fault_work_orders", id, updated);
    }

    private void updateOperationLogTime(Long id, Instant createdAt) {
        int updated = jdbcTemplate.update(
                "update operation_logs set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                id);
        requireSingleSeedUpdate("operation_logs", id, updated);
    }

    private void requireSingleSeedUpdate(String table, Long id, int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected one demo row update in " + table + " for id " + id + ", but updated " + updated);
        }
    }
}
