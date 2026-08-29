package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.SystemAccountInitializer;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentDiscrepancyWorkOrderIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private FaultWorkOrderRepository workOrderRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
    }

    @Test
    void paymentDiscrepancyIsVisibleOnlyToAdminAndCannotBeClaimedAsRepairWork() throws Exception {
        Seed seed = seedDiscrepancy("DISC-POOL-001");

        assertThat(listContains("admin", "admin123", seed.equipmentId(), seed.workOrderId())).isTrue();
        assertThat(listContains("teacher", "teacher123", seed.equipmentId(), seed.workOrderId())).isFalse();
        assertThat(listContains("technician", "tech123", seed.equipmentId(), seed.workOrderId())).isFalse();
        assertThat(listContains("student", "student123", seed.equipmentId(), seed.workOrderId())).isFalse();

        mockMvc.perform(patch("/api/work-orders/{id}/claim", seed.workOrderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CATEGORY_FORBIDDEN"));

        mockMvc.perform(patch("/api/work-orders/{id}/status", seed.workOrderId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"ASSIGNED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_CATEGORY_FORBIDDEN"));
    }

    @Test
    void adminResolvesPaymentDiscrepancyWithoutTakingEquipmentOffline() throws Exception {
        Seed seed = seedDiscrepancy("DISC-ADMIN-001");

        transitionAsAdmin(seed.workOrderId(), "IN_PROGRESS", "IN_PROGRESS");
        assertThat(equipmentRepository.findById(seed.equipmentId()).orElseThrow().getStatus())
                .isEqualTo(EquipmentStatus.AVAILABLE);

        transitionAsAdmin(seed.workOrderId(), "RESOLVED", "RESOLVED");
        transitionAsAdmin(seed.workOrderId(), "CLOSED", "CLOSED");

        FaultWorkOrder closed = workOrderRepository.findById(seed.workOrderId()).orElseThrow();
        assertThat(closed.isEquipmentTakenOffline()).isFalse();
        assertThat(equipmentRepository.findById(seed.equipmentId()).orElseThrow().getStatus())
                .isEqualTo(EquipmentStatus.AVAILABLE);
    }

    private void transitionAsAdmin(Long workOrderId, String target, String expected) throws Exception {
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.category").value("PAYMENT_DISCREPANCY"));
    }

    private boolean listContains(String username, String password, Long equipmentId, Long workOrderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/work-orders")
                        .param("equipmentId", equipmentId.toString())
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password)))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        return content.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == workOrderId);
    }

    private Seed seedDiscrepancy(String code) throws Exception {
        MvcResult equipmentResult = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "差账隔离测试设备-" + code,
                                "category", "综合仪器",
                                "location", "实验楼 D501"))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> equipmentBody = objectMapper.readValue(
                equipmentResult.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        Long equipmentId = ((Number) equipmentBody.get("id")).longValue();
        Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow();
        Long systemUserId = userRepository.findByUsername(SystemAccountInitializer.SYSTEM_USERNAME)
                .orElseThrow()
                .getId();

        FaultWorkOrder discrepancy = FaultWorkOrder.discrepancy(
                equipment,
                systemUserId,
                "test|payment-discrepancy-isolation|" + equipmentId,
                "[对账差异] 测试订单",
                "仅用于验证财务差账工单不会混进普通维修流程。",
                WorkOrderPriority.HIGH);
        Long workOrderId = workOrderRepository.saveAndFlush(discrepancy).getId();
        return new Seed(equipmentId, workOrderId);
    }

    private record Seed(Long equipmentId, Long workOrderId) {
    }
}
