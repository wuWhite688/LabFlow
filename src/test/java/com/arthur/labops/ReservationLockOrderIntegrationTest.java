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

    @Test
    void approvalLocksEquipmentBeforeReservation() throws Exception {
        TestAuth.clearCache();
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");

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
        Long equipmentId = idFrom(equipmentResult);

        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        MvcResult reservationResult = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "验证数据库锁顺序",
                                "startTime", start.toString(),
                                "endTime", start.plus(1, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        Long reservationId = idFrom(reservationResult);

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
