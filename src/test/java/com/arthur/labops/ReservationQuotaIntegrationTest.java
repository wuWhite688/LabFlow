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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.reservation-max-active-per-user=1",
        "labops.reservation-max-advance=30d"
})
@AutoConfigureMockMvc
class ReservationQuotaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @BeforeEach
    void releaseActiveReservations() {
        TestAuth.clearCache();
        java.util.List<Reservation> stored = reservationRepository.findAll();
        for (Reservation reservation : stored) {
            if (reservation.getStatus() == ReservationStatus.PENDING
                    || reservation.getStatus() == ReservationStatus.APPROVED) {
                reservation.cancel();
            }
        }
        reservationRepository.saveAll(stored);
        reservationRepository.flush();
    }

    @Test
    void secondActiveReservationIsRejectedUntilSlotIsReleased() throws Exception {
        Long firstEquipment = createEquipment("quota-a");
        Long secondEquipment = createEquipment("quota-b");
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");

        Long firstId = createReservation(firstEquipment, start, student, "配额第一单");
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(secondEquipment, start.plus(3, ChronoUnit.HOURS), "配额超限")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_QUOTA_EXCEEDED"));

        mockMvc.perform(patch("/api/reservations/{id}/cancel", firstId)
                        .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(status().isOk());
        assertThat(reservationRepository.findById(firstId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(secondEquipment, start.plus(3, ChronoUnit.HOURS), "取消后重订")))
                .andExpect(status().isCreated());
    }

    @Test
    void otherUsersAreNotAffectedBySomeoneElsesQuota() throws Exception {
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        createReservation(createEquipment("quota-student"), start,
                bearer(mockMvc, objectMapper, "student", "student123"), "学生占配额");
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(createEquipment("quota-teacher"), start, "教师独立配额")))
                .andExpect(status().isCreated());
    }

    @Test
    void startTimeBeyondAdvanceWindowIsRejected() throws Exception {
        Instant tooFar = Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(createEquipment("quota-far"), tooFar, "过远预约")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESERVATION_TOO_FAR_AHEAD"));
    }

    @Test
    void concurrentCreatesCannotBothTakeTheLastQuotaSlot() throws Exception {
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long left = createEquipment("quota-race-a");
        Long right = createEquipment("quota-race-b");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return createStatus(left, start, student, "并发配额A");
            });
            Future<Integer> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return createStatus(right, start.plus(4, ChronoUnit.HOURS), student, "并发配额B");
            });
            List<Integer> statuses = List.of(getOrTimeout(first), getOrTimeout(second));
            assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
            assertThat(statuses).contains(409);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void completedReservationReleasesQuota() throws Exception {
        Instant start = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        Long id = createReservation(createEquipment("quota-done"), start, student, "完成后释放");
        mockMvc.perform(patch("/api/reservations/{id}/decision", id)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/reservations/{id}/complete", id)
                        .header(HttpHeaders.AUTHORIZATION, teacher))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(createEquipment("quota-done-2"), start, "完成后新单")))
                .andExpect(status().isCreated());
    }

    @Test
    void expiredReservationReleasesQuota() throws Exception {
        Long equipmentId = createEquipment("quota-exp");
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        Long id = createReservation(equipmentId, start, student, "将被过期");
        Reservation row = reservationRepository.findById(id).orElseThrow();
        row.expireIfPending(Instant.now().plus(1, ChronoUnit.HOURS));
        reservationRepository.saveAndFlush(row);
        assertThat(reservationRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(createEquipment("quota-exp-2"), start, "过期后新单")))
                .andExpect(status().isCreated());
    }

    private int createStatus(Long equipmentId, Instant start, String bearer, String purpose) throws Exception {
        return mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(equipmentId, start, purpose)))
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
                                "code", "Q-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "配额测试-" + label,
                                "category", "并发测试",
                                "location", "实验楼 Q101"))))
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
            throw new AssertionError("quota race timed out", timeout);
        }
    }
}
