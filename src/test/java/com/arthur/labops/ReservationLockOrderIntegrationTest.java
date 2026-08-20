package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.expiry.ReservationExpiryCompensationJob;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.arthur.labops.SqlCaptureStatementInspector")
@AutoConfigureMockMvc
class ReservationLockOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationExpiryCompensationJob compensationJob;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Test
    void approvalLocksEquipmentBeforeReservation() throws Exception {
        TestAuth.clearCache();
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");

        Long equipmentId = createEquipment(admin);
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(student, equipmentId, start, "验证数据库锁顺序");

        SqlCaptureStatementInspector.clear();
        mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        List<String> sql = SqlCaptureStatementInspector.snapshot();
        int equipmentLock = firstIndex(sql,
                statement -> statement.contains(" from equipment ") && statement.contains(" for update"));
        int reservationLock = firstIndex(sql,
                statement -> statement.contains(" from equipment_reservations ") && statement.contains(" for update"));

        assertThat(equipmentLock)
                .as("equipment row should be pessimistically locked during reservation state change; SQL=%s", sql)
                .isGreaterThanOrEqualTo(0);
        assertThat(reservationLock)
                .as("reservation row should be pessimistically locked during reservation state change; SQL=%s", sql)
                .isGreaterThanOrEqualTo(0);
        assertThat(equipmentLock)
                .as("lock order must stay Equipment -> Reservation to match work-order creation; SQL=%s", sql)
                .isLessThan(reservationLock);
    }

    @Test
    void workOrderLocksMultipleReservationsInPrimaryKeyOrder() throws Exception {
        TestAuth.clearCache();
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");

        Long equipmentId = createEquipment(admin);
        Instant firstStart = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createReservation(student, equipmentId, firstStart, "第一条待取消预约");
        createReservation(student, equipmentId, firstStart.plus(3, ChronoUnit.HOURS), "第二条待取消预约");

        SqlCaptureStatementInspector.clear();
        mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "锁顺序测试故障",
                                "description", "验证批量预约悲观锁按主键排序",
                                "priority", "HIGH"))))
                .andExpect(status().isCreated());

        List<String> sql = SqlCaptureStatementInspector.snapshot();
        String batchReservationLock = sql.stream()
                .filter(statement -> statement.contains(" from equipment_reservations "))
                .filter(statement -> statement.contains(" for update"))
                .filter(statement -> statement.contains(" order by "))
                .findFirst()
                .orElse("");

        assertThat(batchReservationLock)
                .as("batch reservation lock must keep an ORDER BY primary-key clause; SQL=%s", sql)
                .isNotBlank()
                .contains(" order by ")
                .contains(".id");
    }

    @Test
    void compensationExpiresWithEquipmentLockedBeforeReservation() {
        TestAuth.clearCache();
        Equipment equipment = equipmentRepository.save(new Equipment(
                "LOCK-EXP-" + UUID.randomUUID().toString().substring(0, 8),
                "补偿锁顺序设备",
                "并发测试",
                "实验楼 L102"));
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Reservation overdue = reservationRepository.saveAndFlush(new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                "补偿任务锁顺序",
                start,
                start.plus(1, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.SECONDS)));

        SqlCaptureStatementInspector.clear();
        compensationJob.expireOverduePendingReservations();

        List<String> sql = SqlCaptureStatementInspector.snapshot();
        int equipmentLock = firstIndex(sql,
                statement -> statement.contains(" from equipment ") && statement.contains(" for update"));
        int reservationLock = firstIndex(sql,
                statement -> statement.contains(" from equipment_reservations ") && statement.contains(" for update"));

        assertThat(equipmentLock)
                .as("compensation expire must lock equipment; SQL=%s", sql)
                .isGreaterThanOrEqualTo(0);
        assertThat(reservationLock)
                .as("compensation expire must lock reservation; SQL=%s", sql)
                .isGreaterThanOrEqualTo(0);
        assertThat(equipmentLock)
                .as("compensation lock order must stay Equipment -> Reservation; SQL=%s", sql)
                .isLessThan(reservationLock);
        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus().name())
                .isEqualTo("EXPIRED");
    }

    private Long createEquipment(String admin) throws Exception {
        String code = "LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult equipmentResult = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "锁顺序测试设备",
                                "category", "并发测试",
                                "location", "实验楼 L101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(equipmentResult);
    }

    private Long createReservation(String student, Long equipmentId, Instant start, String purpose) throws Exception {
        MvcResult reservationResult = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", purpose,
                                "startTime", start.toString(),
                                "endTime", start.plus(1, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(reservationResult);
    }

    private int firstIndex(List<String> values, Predicate<String> predicate) {
        for (int index = 0; index < values.size(); index++) {
            if (predicate.test(values.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
