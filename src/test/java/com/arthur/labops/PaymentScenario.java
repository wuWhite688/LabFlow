package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Drives a priced reservation up to the point where money is expected, so the
 * payment tests can start from "an order is open" without repeating six calls.
 */
final class PaymentScenario {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    PaymentScenario(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    Long createPricedEquipment(String codePrefix, long hourlyPriceCents) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", codePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("name", "计价设备");
        body.put("category", "分析仪器");
        body.put("location", "A301");
        body.put("hourlyPriceCents", hourlyPriceCents);
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.valueOf(String.valueOf(read(result).get("id")));
    }

    /** Creates a reservation of {@code hours} starting an hour from now. */
    Long createReservation(Long equipmentId, long hours) throws Exception {
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "支付链路测试",
                                "startTime", start.toString(),
                                "endTime", start.plus(hours, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.valueOf(String.valueOf(read(result).get("id")));
    }

    /** Approves as teacher. A priced reservation lands in AWAITING_PAYMENT, not APPROVED. */
    Map<String, Object> approve(Long reservationId) throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/reservations/" + reservationId + "/decision")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "APPROVED"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    Map<String, Object> cancelAsStudent(Long reservationId) throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/reservations/" + reservationId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123")))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private Map<String, Object> read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
    }
}
