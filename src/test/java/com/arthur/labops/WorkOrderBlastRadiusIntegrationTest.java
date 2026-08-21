package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A non-privileged fault report deliberately leaves the equipment usable: only the
 * reporter's own reservations are cancelled and the equipment is not taken offline.
 *
 * <p>That limit used to survive only until the next status sync. {@code sync()}
 * derived MAINTENANCE from <em>any</em> active work order, and
 * {@code ReservationExpiryCompensationJob} syncs every equipment holding an approved
 * reservation every 30s — so a student report silently took popular equipment offline
 * one scan interval later, blocking all new reservations.
 *
 * <p>These tests call {@code sync()} directly, which is what the scheduled job does.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderBlastRadiusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentStatusService equipmentStatusService;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Test
    void studentReportLeavesEquipmentAvailableAcrossStatusSync() throws Exception {
        Long equipmentId = createEquipment("BLAST-001");

        createWorkOrderAs("student", "student123", equipmentId);
        assertStatus(equipmentId, EquipmentStatus.AVAILABLE);

        equipmentStatusService.sync(equipmentId);

        assertStatus(equipmentId, EquipmentStatus.AVAILABLE);
    }

    @Test
    void studentReportedEquipmentStaysReservableAfterStatusSync() throws Exception {
        Long equipmentId = createEquipment("BLAST-002");
        createWorkOrderAs("student", "student123", equipmentId);

        equipmentStatusService.sync(equipmentId);

        // The user-visible consequence: other users can still book the equipment.
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "报修后仍可预约",
                                "startTime", java.time.Instant.now().plusSeconds(7200).toString(),
                                "endTime", java.time.Instant.now().plusSeconds(10800).toString()))))
                .andExpect(status().isCreated());
    }

    @Test
    void privilegedReportKeepsEquipmentOfflineAcrossStatusSync() throws Exception {
        Long equipmentId = createEquipment("BLAST-003");

        createWorkOrderAs("teacher", "teacher123", equipmentId);
        assertStatus(equipmentId, EquipmentStatus.MAINTENANCE);

        equipmentStatusService.sync(equipmentId);

        assertStatus(equipmentId, EquipmentStatus.MAINTENANCE);
    }

    @Test
    void claimingAStudentReportTakesEquipmentOfflineAndSurvivesSync() throws Exception {
        Long equipmentId = createEquipment("BLAST-004");
        Long workOrderId = createWorkOrderAs("student", "student123", equipmentId);
        assertStatus(equipmentId, EquipmentStatus.AVAILABLE);

        mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "technician", "tech123")))
                .andExpect(status().isOk());
        assertStatus(equipmentId, EquipmentStatus.MAINTENANCE);

        equipmentStatusService.sync(equipmentId);

        assertStatus(equipmentId, EquipmentStatus.MAINTENANCE);
    }

    private void assertStatus(Long equipmentId, EquipmentStatus expected) {
        assertThat(equipmentRepository.findById(equipmentId).orElseThrow().getStatus())
                .isEqualTo(expected);
    }

    private Long createEquipment(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "爆炸半径测试设备-" + code,
                                "category", "综合仪器",
                                "location", "实验楼 R201"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createWorkOrderAs(String username, String password, Long equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "爆炸半径测试工单",
                                "description", "验证报修人角色如何影响设备状态同步",
                                "priority", "MEDIUM"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return Long.valueOf(String.valueOf(body.get("id")));
    }
}
