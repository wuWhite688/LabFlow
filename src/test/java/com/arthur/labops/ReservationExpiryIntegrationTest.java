package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arthur.labops.audit.OperationLogRepository;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;

@SpringBootTest(properties = "labops.reservation-approval-timeout=300ms")
@AutoConfigureMockMvc
class ReservationExpiryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Autowired
    private OperationLogRepository operationLogRepository;

    @Test
    void pendingReservationExpiresButApprovedReservationStaysApproved() throws Exception {
        Long pendingId = createReservation(createEquipment("EXPIRY-001"), "待过期预约");
        Long approvedId = createReservation(createEquipment("EXPIRY-002"), "已审批预约");

        mockMvc.perform(patch("/api/reservations/{id}/decision", approvedId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        Map<Long, String> statuses = waitForExpiry(pendingId, approvedId);
        assertThat(statuses.get(pendingId)).isEqualTo("EXPIRED");
        assertThat(statuses.get(approvedId)).isEqualTo("APPROVED");
    }

    @Test
    void decisionOnOverduePendingReservationCommitsExpiryAndAuditBeforeReturningConflict() throws Exception {
        Equipment equipment = equipmentRepository.save(new Equipment(
                "EXPIRY-DECIDE-" + System.nanoTime(),
                "审批过期测试设备",
                "测试仪器",
                "综合楼 D102"));
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Reservation overdue = reservationRepository.saveAndFlush(new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                "验证审批过期事务提交",
                start,
                start.plus(1, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.SECONDS)));

        mockMvc.perform(patch("/api/reservations/{id}/decision", overdue.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_EXPIRED"));

        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(operationLogRepository.findAll().stream()
                .filter(log -> "RESERVATION_EXPIRED".equals(log.getAction()))
                .filter(log -> overdue.getId().equals(log.getTargetId())))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getActorUsername()).isEqualTo("system");
                    assertThat(log.getActorRole()).isEqualTo("SYSTEM");
                });
    }

    @Test
    void decisionAfterRequestedEndExpiresLegacyPendingReservationInsteadOfApprovingIt() throws Exception {
        Equipment equipment = equipmentRepository.save(new Equipment(
                "EXPIRY-END-" + System.nanoTime(),
                "结束后审批测试设备",
                "测试仪器",
                "综合楼 D103"));
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        Instant end = Instant.now().minus(1, ChronoUnit.SECONDS);
        Reservation ended = new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                "验证预约结束后不能审批",
                end.minus(1, ChronoUnit.HOURS),
                end,
                Instant.now().plus(1, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(ended, "expiresAt", Instant.now().plus(1, ChronoUnit.HOURS));
        ended = reservationRepository.saveAndFlush(ended);
        Long endedId = ended.getId();

        mockMvc.perform(patch("/api/reservations/{id}/decision", endedId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_EXPIRED"));

        assertThat(reservationRepository.findById(endedId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(operationLogRepository.findAll().stream()
                .filter(log -> "RESERVATION_EXPIRED".equals(log.getAction()))
                .filter(log -> endedId.equals(log.getTargetId())))
                .singleElement();
    }

    private Map<Long, String> waitForExpiry(Long pendingId, Long approvedId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        Map<Long, String> statuses;
        do {
            statuses = reservationStatuses(pendingId, approvedId);
            if ("EXPIRED".equals(statuses.get(pendingId))) {
                return statuses;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return statuses;
    }

    private Map<Long, String> reservationStatuses(Long firstId, Long secondId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123")))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> page = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reservations = (List<Map<String, Object>>) page.get("content");
        return reservations.stream()
                .filter(item -> firstId.equals(((Number) item.get("id")).longValue())
                        || secondId.equals(((Number) item.get("id")).longValue()))
                .collect(java.util.stream.Collectors.toMap(
                        item -> ((Number) item.get("id")).longValue(),
                        item -> (String) item.get("status")));
    }

    private Long createEquipment(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "预约过期测试设备",
                                "category", "测试仪器",
                                "location", "综合楼 D101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createReservation(Long equipmentId, String purpose) throws Exception {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", purpose,
                                "startTime", start.toString(),
                                "endTime", start.plus(1, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
