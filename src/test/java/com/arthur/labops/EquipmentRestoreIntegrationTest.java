package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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

/**
 * Restoring equipment that was never retired is a client-side conflict, not a
 * server fault. Equipment.restoreFromRetired guards the transition with an
 * IllegalStateException, which GlobalExceptionHandler does not map — without a
 * BusinessException ahead of it the endpoint answered 500 and logged
 * "Unhandled server exception", which would page an on-call for a bad request.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentRestoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void restoringNonRetiredEquipmentConflictsInsteadOfFailing() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        Long equipmentId = createEquipment(admin);

        mockMvc.perform(patch("/api/equipment/{id}/restore", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_RETIRED"));
    }

    @Test
    void retireThenRestoreReturnsEquipmentToAvailable() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        Long equipmentId = createEquipment(admin);

        mockMvc.perform(patch("/api/equipment/{id}/retire", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));

        mockMvc.perform(patch("/api/equipment/{id}/restore", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        // Second restore now conflicts rather than throwing, same as the first test.
        mockMvc.perform(patch("/api/equipment/{id}/restore", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_RETIRED"));
    }

    private Long createEquipment(String admin) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "RST-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "恢复流程测试设备",
                                "category", "状态机测试",
                                "location", "实验楼 E201"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                created.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
