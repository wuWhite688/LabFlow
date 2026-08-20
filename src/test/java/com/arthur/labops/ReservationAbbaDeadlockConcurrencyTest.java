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

/**
 * Live ABBA races between reservation state changes and work-order creation.
 *
 * <p>Both paths lock Equipment and Reservation. The invariant is
 * {@code Equipment -> Reservation} (and multi-row reservations {@code ORDER BY id}).
 * If state changes go back to {@code Reservation -> Equipment}, or the batch query
 * drops {@code ORDER BY id} while a second locker walks ids in another order,
 * InnoDB deadlocks. {@code Future.get(10, SECONDS)} turns that hang into a failed test.
 *
 * <p>H2 can verify: both threads return, no 500, and (with {@code @Version}) no lost
 * update. H2 cannot verify MySQL deadlock detection — its lock manager may complete
 * a reversed order without deadlock. A pass on CI H2 is necessary but not sufficient
 * for InnoDB; a {@link TimeoutException} is still a hard fail. The SQL-order test in
 * {@link ReservationLockOrderIntegrationTest} remains the CI-stable ORDER BY check.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationAbbaDeadlockConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Invariant: approve and fault-report on the same equipment both finish; they
     * must not deadlock. Business winner is either APPROVED or CANCELLED (student
     * work order cancels own open bookings).
     * Trigger: CyclicBarrier, teacher PATCH /decision APPROVED vs student POST
     * /work-orders for the same equipment/reservation.
     * If lock order reverts to Reservation-then-Equipment on decide, this pairs
     * with work-order Equipment-then-Reservation and deadlocks (test times out).
     */
    @Test
    void concurrentApproveAndWorkOrderCreateDoNotDeadlock() throws Exception {
        Long equipmentId = createEquipment("abba-approve");
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, "ABBA审批");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approved = executor.submit(() -> {
                barrier.await();
                return decide(reservationId, teacher);
            });
            Future<Integer> reported = executor.submit(() -> {
                barrier.await();
                return createWorkOrder(equipmentId, student, "ABBA报修-审批");
            });

            int approveStatus = getOrTimeout(approved, "approve vs work-order");
            int workOrderStatus = getOrTimeout(reported, "approve vs work-order");
            assertThat(List.of(approveStatus, workOrderStatus)).allMatch(code -> code < 500);
            assertThat(approveStatus).isIn(200, 409);
            assertThat(workOrderStatus).isIn(201, 409);
        } finally {
            shutdownRace(executor);
        }
    }

    /**
     * Invariant: complete (Equipment then one high-id reservation) and a privileged
     * work-order (Equipment then all reservations ORDER BY id) both finish.
     * Trigger: two APPROVED rows on one device; teacher completes the *higher* id
     * while admin files a work order that batch-locks both rows ascending.
     * If {@code ORDER BY id} is removed and state change locks the high id first
     * (or locks reservation before equipment), the two lock sequences can form a
     * cycle and this test times out.
     */
    @Test
    void concurrentCompleteOfHigherIdAndBatchWorkOrderDoNotDeadlock() throws Exception {
        Long equipmentId = createEquipment("abba-batch");
        Instant firstStart = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long lowerId = createReservation(equipmentId, firstStart, "低id预约");
        Long higherId = createReservation(equipmentId, firstStart.plus(3, ChronoUnit.HOURS), "高id预约");
        assertThat(higherId).isGreaterThan(lowerId);

        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        approve(lowerId, teacher);
        approve(higherId, teacher);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> completed = executor.submit(() -> {
                barrier.await();
                return complete(higherId, teacher);
            });
            Future<Integer> reported = executor.submit(() -> {
                barrier.await();
                return createWorkOrder(equipmentId, admin, "ABBA报修-批量");
            });

            int completeStatus = getOrTimeout(completed, "complete higher-id vs batch work-order");
            int workOrderStatus = getOrTimeout(reported, "complete higher-id vs batch work-order");
            assertThat(List.of(completeStatus, workOrderStatus)).allMatch(code -> code < 500);
            assertThat(completeStatus).isIn(200, 409);
            assertThat(workOrderStatus).isIn(201, 409);
        } finally {
            shutdownRace(executor);
        }
    }

    /**
     * Invariant: cancel of the later reservation and a second thread's work-order
     * batch lock walk ids in opposite *business* order (high id first vs ORDER BY id)
     * but the same *lock* order (Equipment first, then reservations ascending).
     * Trigger: two PENDING rows; student cancels the higher id while teacher
     * creates a work order that {@code FOR UPDATE} both rows {@code ORDER BY id}.
     * If the batch query drops {@code ORDER BY id} and acquires the high id first
     * while cancel still takes Equipment then that high id, MySQL can deadlock.
     */
    @Test
    void concurrentCancelHigherIdAndWorkOrderBatchDoNotDeadlock() throws Exception {
        Long equipmentId = createEquipment("abba-cancel-batch");
        Instant firstStart = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long lowerId = createReservation(equipmentId, firstStart, "低id待取消");
        Long higherId = createReservation(equipmentId, firstStart.plus(3, ChronoUnit.HOURS), "高id待取消");
        assertThat(higherId).isGreaterThan(lowerId);

        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> cancelled = executor.submit(() -> {
                barrier.await();
                return cancel(higherId, student);
            });
            Future<Integer> reported = executor.submit(() -> {
                barrier.await();
                return createWorkOrder(equipmentId, teacher, "ABBA报修-高id取消");
            });

            int cancelStatus = getOrTimeout(cancelled, "cancel higher-id vs batch work-order");
            int workOrderStatus = getOrTimeout(reported, "cancel higher-id vs batch work-order");
            assertThat(List.of(cancelStatus, workOrderStatus)).allMatch(code -> code < 500);
            assertThat(cancelStatus).isIn(200, 409);
            assertThat(workOrderStatus).isIn(201, 409);
        } finally {
            shutdownRace(executor);
        }
    }

    private int getOrTimeout(Future<Integer> future, String race) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError(
                    "Deadlock suspected (" + race + "): future did not complete in 10s. "
                            + "Likely Equipment/Reservation lock order was reverted to an ABBA cycle.",
                    timeout);
        }
    }

    private static void shutdownRace(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker threads did not terminate; possible database deadlock");
        }
    }

    private int decide(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int complete(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/complete", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int cancel(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/cancel", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int createWorkOrder(Long equipmentId, String bearer, String title) throws Exception {
        return mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", title,
                                "description", "并发 ABBA 死锁探测，不依赖 sleep",
                                "priority", "HIGH"))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void approve(Long reservationId, String teacher) throws Exception {
        mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk());
    }

    private Long createEquipment(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "AB-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "ABBA测试-" + label,
                                "category", "并发测试",
                                "location", "实验楼 D101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createReservation(Long equipmentId, Instant start, String purpose) throws Exception {
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
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
