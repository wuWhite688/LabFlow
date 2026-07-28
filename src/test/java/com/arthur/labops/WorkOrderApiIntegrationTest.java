package com.arthur.labops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.arthur.labops.TestAuth.bearer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkOrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void workOrderLifecycleBlocksThenRestoresReservations() throws Exception {
        Long equipmentId = createEquipment("LASER-001");
        Long workOrderId = createWorkOrder(equipmentId);

        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservation(equipmentId, start, start.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_UNAVAILABLE"));

        Long technicianId = technicianId("technician", "tech123");
        transitionAs("admin", "admin123", workOrderId, "ASSIGNED", technicianId, "ASSIGNED");
        transitionAs("technician", "tech123", workOrderId, "IN_PROGRESS", null, "IN_PROGRESS");
        transitionAs("technician", "tech123", workOrderId, "RESOLVED", null, "RESOLVED");
        transitionAs("technician", "tech123", workOrderId, "CLOSED", null, "CLOSED");

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservation(equipmentId, start, start.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsIllegalStatusJump() throws Exception {
        Long workOrderId = createWorkOrder(createEquipment("LASER-002"));
        Long technicianId = technicianId("technician", "tech123");
        transitionAs("admin", "admin123", workOrderId, "ASSIGNED", technicianId, "ASSIGNED");

        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetStatus", "RESOLVED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_WORK_ORDER_TRANSITION"));
    }

    @Test
    void technicianCanClaimSelfButNotAssignOthers() throws Exception {
        Long workOrderId = createWorkOrder(createEquipment("LASER-003"));
        Long tech1 = technicianId("technician", "tech123");
        Long tech2 = technicianId("technician2", "tech2123");

        // 维修员不能把单派给别人
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "ASSIGNED",
                                "assigneeId", tech2))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLAIM_SELF_ONLY"));

        // 管理员也不能被“接单”接口冒充
        mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CLAIM_FORBIDDEN"));

        // 维修员主动接单给自己
        mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assigneeId").value(tech1));

        // 再次接单冲突
        mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician2", "tech2123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_CLAIMED"));

        // tech2 不能处理 tech1 的单
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician2", "tech2123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetStatus", "IN_PROGRESS"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_ASSIGNED"));

        transitionAs("technician", "tech123", workOrderId, "IN_PROGRESS", null, "IN_PROGRESS");
    }

    @Test
    void adminCanStillAssignAnyTechnician() throws Exception {
        Long workOrderId = createWorkOrder(createEquipment("LASER-004"));
        Long tech2 = technicianId("technician2", "tech2123");
        // transitionAs 已校验状态与 assigneeId；再确认被派单维修员能在列表中看到
        transitionAs("admin", "admin123", workOrderId, "ASSIGNED", tech2, "ASSIGNED");

        mockMvc.perform(get("/api/work-orders")
                        .param("status", "ASSIGNED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician2", "tech2123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[*].assigneeId", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(tech2.intValue()))));
    }

    private Long createEquipment(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "激光共聚焦显微镜",
                                "category", "光学成像",
                                "location", "生命楼 B316"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createWorkOrder(Long equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "激光器无法正常启动",
                                "description", "电源指示灯闪烁，设备自检失败",
                                "priority", "HIGH"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        return idFrom(result);
    }

    private void transitionAs(String username, String password, Long id, String statusValue,
                              Long assigneeId, String expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetStatus", statusValue);
        if (assigneeId != null) {
            body.put("assigneeId", assigneeId);
        }
        mockMvc.perform(patch("/api/work-orders/{id}/status", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected));
    }

    private String reservation(Long equipmentId, Instant start, Instant end) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "equipmentId", equipmentId,
                "purpose", "细胞荧光成像",
                "startTime", start.toString(),
                "endTime", end.toString()));
    }

    private Long technicianId(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password)))
                .andExpect(status().isOk())
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
