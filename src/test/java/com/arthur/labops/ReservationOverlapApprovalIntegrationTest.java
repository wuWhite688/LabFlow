package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
class ReservationOverlapApprovalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void overlappingPendingReservationsCanBothBeCreatedThenFirstApproveBlocksSecond() throws Exception {
        Long equipmentId = createEquipment("overlap-seq");
        Instant start = Instant.now().plus(11, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");

        Long firstId = createReservation(equipmentId, start, student, "重叠待审批A");
        Long secondId = createReservation(equipmentId, start.plus(30, ChronoUnit.MINUTES), student, "重叠待审批B");

        mockMvc.perform(patch("/api/reservations/{id}/decision", firstId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(patch("/api/reservations/{id}/decision", secondId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(equipmentId, start, "已批准时段再订")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
    }

    @Test
    void concurrentApproveOfOverlappingPendingYieldsExactlyOneSuccess() throws Exception {
        Long equipmentId = createEquipment("overlap-race");
        Instant start = Instant.now().plus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        Long firstId = createReservation(equipmentId, start, student, "并发重叠A");
        Long secondId = createReservation(equipmentId, start.plus(20, ChronoUnit.MINUTES), student, "并发重叠B");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return decideStatus(firstId, teacher);
            });
            Future<Integer> right = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return decideStatus(secondId, admin);
            });
            List<Integer> statuses = List.of(getOrTimeout(left), getOrTimeout(right));
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(statuses).contains(409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int decideStatus(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Long createReservation(Long equipmentId, Instant start, String bearer, String purpose) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(equipmentId, start, purpose)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }

    private Long createEquipment(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "OV-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "重叠审批-" + label,
                                "category", "并发测试",
                                "location", "实验楼 O101"))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }

    private String reservationJson(Long equipmentId, Instant start, String purpose) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "equipmentId", equipmentId,
                "purpose", purpose,
                "startTime", start.toString(),
                "endTime", start.plus(1, ChronoUnit.HOURS).toString()));
    }

    private static int getOrTimeout(Future<Integer> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("overlapping approve timed out", timeout);
        }
    }
}
