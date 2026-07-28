package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class BusinessRulesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rejectsPastReservationAndTooLongDuration() throws Exception {
        Long equipmentId = createEquipment("RULE-001");

        Instant past = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(equipmentId, past, past.plus(1, ChronoUnit.HOURS), "过去时段")))
                .andExpect(status().isBadRequest());

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(equipmentId, start, start.plus(13, ChronoUnit.HOURS), "超长预约")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESERVATION_DURATION_TOO_LONG"));
    }

    @Test
    void workOrderCancelsOpenReservationsAndAssigneeMustBeTechnician() throws Exception {
        Long equipmentId = createEquipment("RULE-002");
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, "将被报修取消的预约");

        mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "真空泵异响",
                                "description", "运行 10 分钟后出现持续异响",
                                "priority", "HIGH"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(get("/api/reservations")
                        .param("status", "CANCELLED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + reservationId + ")].status").value("CANCELLED"));

        mockMvc.perform(get("/api/equipment")
                        .param("keyword", "RULE-002")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("MAINTENANCE"));

        Long workOrderId = latestWorkOrderId();
        Long studentId = userId("student", "student123");
        // 维修员不能把单派给非自己（含学生）
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "ASSIGNED",
                                "assigneeId", studentId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLAIM_SELF_ONLY"));

        // 管理员也不能把单派给学生
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "ASSIGNED",
                                "assigneeId", studentId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_TECHNICIAN"));

        Long technicianId = userId("technician", "tech123");
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "ASSIGNED",
                                "assigneeId", technicianId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assigneeId").value(technicianId));
    }

    @Test
    void equipmentRetireAndRestore() throws Exception {
        Long equipmentId = createEquipment("RULE-003");
        mockMvc.perform(patch("/api/equipment/{id}/retire", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(
                                equipmentId,
                                Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                                Instant.now().plus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS),
                                "退役后不可预约")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_UNAVAILABLE"));

        mockMvc.perform(patch("/api/equipment/{id}/restore", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    private Long createEquipment(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "规则测试设备-" + code,
                                "category", "综合仪器",
                                "location", "实验楼 R101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createReservation(Long equipmentId, Instant start, String purpose) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(equipmentId, start, start.plus(1, ChronoUnit.HOURS), purpose)))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private String reservationBody(Long equipmentId, Instant start, Instant end, String purpose) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("equipmentId", equipmentId);
        body.put("purpose", purpose);
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        return objectMapper.writeValueAsString(body);
    }

    private Long userId(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return idFrom(result);
    }

    private Long latestWorkOrderId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/work-orders")
                        .param("size", "1")
                        .param("sort", "createdAt,desc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((java.util.List<Map<String, Object>>) body.get("content")).get(0);
        return ((Number) first.get("id")).longValue();
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
