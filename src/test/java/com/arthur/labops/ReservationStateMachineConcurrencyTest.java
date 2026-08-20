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
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.OptimisticLockException;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reservation state-machine races that the existing sequential API tests do not cover.
 *
 * <p>CI ({@code ./mvnw verify} on GitHub Actions): default profile is H2 + local lock
 * + local expiry. All tests in this class run in CI. Approve/cancel/complete races
 * used to need MySQL {@code FOR UPDATE}. {@code Reservation.version} now fails a
 * lost update on H2 without row locks. Pessimistic locks are unchanged and remain
 * the primary guarantee on MySQL. Complementary HTTP pairs (double approve,
 * complete vs cancel) still assert 200+409 through MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationStateMachineConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Invariant: two writers that observed the same {@code Reservation.version}
     * cannot both commit; exactly one mutation wins, the other is an optimistic
     * lock failure (HTTP 409 {@code RESOURCE_BUSY} via {@code GlobalExceptionHandler}).
     * Trigger: two {@code REQUIRES_NEW} transactions {@code findById} (no
     * {@code PESSIMISTIC_WRITE}), {@link CyclicBarrier}, then approve vs cancel
     * and flush. Pessimistic locks are skipped on purpose so this is the H2
     * lost-update path {@code @Version} exists for — HTTP decide/cancel can
     * serialize, and cancel of APPROVED is a legal second 200.
     * Failure: {@code @Version} missing (both commit) or the exception is not
     * translated to {@code OptimisticLockingFailureException}.
     */
    @Test
    void concurrentApproveAndCancelLeaveExactlyOneTerminalStatus() throws Exception {
        Equipment equipment = newEquipment("approve-vs-cancel");
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = pending(equipment, start, start.plus(1, ChronoUnit.HOURS), "批准对取消").getId();
        CyclicBarrier loaded = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> approved = executor.submit(() ->
                    mutateSharedVersion(reservationId, loaded, Reservation::approve));
            Future<String> cancelled = executor.submit(() ->
                    mutateSharedVersion(reservationId, loaded, Reservation::cancel));

            List<String> outcomes = List.of(
                    getOrTimeout(approved, "approve vs cancel"),
                    getOrTimeout(cancelled, "approve vs cancel"));
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "conflict");
        } finally {
            shutdownRace(executor);
        }

        ReservationStatus finalStatus = reservationRepository.findById(reservationId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(ReservationStatus.APPROVED, ReservationStatus.CANCELLED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getVersion()).isEqualTo(1L);
    }

    /**
     * Invariant: a single PENDING reservation can be approved at most once.
     * Trigger: admin and teacher both PATCH /decision APPROVED on the same row.
     * Failure: missing {@code FOR UPDATE} on the reservation, or {@code decide}
     * does not reject non-PENDING after the other transaction commits.
     */
    @Test
    void concurrentDoubleApproveYieldsOneSuccessAndOneConflict() throws Exception {
        Long reservationId = createHttpReservation("double-approve");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return decideStatus(reservationId, "APPROVED", teacher);
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return decideStatus(reservationId, "APPROVED", admin);
            });

            List<Integer> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.APPROVED);
    }

    /**
     * Invariant: overlapping PENDING rows cannot both become APPROVED.
     * Trigger: insert two overlapping PENDING rows (bypassing create lock), then
     * two teachers approve them at the same barrier.
     * Note: {@code decide} treats the other PENDING as a conflict, so both approvals
     * may 409 and both rows stay PENDING — still not a double-book. Failure is
     * {@code approved == 2}.
     */
    @Test
    void concurrentApproveOfOverlappingPendingLeavesSingleApproved() throws Exception {
        Equipment equipment = newEquipment("overlap-approve");
        Instant start = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Reservation first = pending(equipment, start, start.plus(2, ChronoUnit.HOURS), "重叠审批A");
        Reservation second = pending(equipment, start.plus(30, ChronoUnit.MINUTES), start.plus(3, ChronoUnit.HOURS), "重叠审批B");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> left = executor.submit(() -> {
                barrier.await();
                return decideStatus(first.getId(), "APPROVED", teacher);
            });
            Future<Integer> right = executor.submit(() -> {
                barrier.await();
                return decideStatus(second.getId(), "APPROVED", admin);
            });

            left.get(10, TimeUnit.SECONDS);
            right.get(10, TimeUnit.SECONDS);
        }

        List<ReservationStatus> stored = List.of(
                reservationRepository.findById(first.getId()).orElseThrow().getStatus(),
                reservationRepository.findById(second.getId()).orElseThrow().getStatus());
        long approved = stored.stream().filter(status -> status == ReservationStatus.APPROVED).count();
        assertThat(approved)
                .as("overlapping PENDING rows must never both become APPROVED; both staying PENDING "
                        + "is the current decide() rule because PENDING is itself a conflicting status")
                .isLessThanOrEqualTo(1);
        assertThat(stored).allMatch(status ->
                status == ReservationStatus.APPROVED || status == ReservationStatus.PENDING);
    }

    /**
     * Invariant: an APPROVED reservation is completed or cancelled, never both.
     * Trigger: teacher PATCH /complete and student PATCH /cancel on the same APPROVED id.
     * Failure: {@code @Version} missing (two 200s on H2) or status guards skipped.
     */
    @Test
    void concurrentCompleteAndCancelOnApprovedLeaveOneTerminalStatus() throws Exception {
        Long reservationId = createHttpReservation("complete-vs-cancel");
        mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> completed = executor.submit(() -> {
                start.await();
                return completeStatus(reservationId, teacher);
            });
            Future<Integer> cancelled = executor.submit(() -> {
                start.await();
                return cancelStatus(reservationId, student);
            });

            List<Integer> statuses = List.of(
                    completed.get(10, TimeUnit.SECONDS),
                    cancelled.get(10, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(statuses).contains(409);
        }

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isIn(ReservationStatus.COMPLETED, ReservationStatus.CANCELLED);
    }

    /**
     * Invariant: the per-equipment lock serializes creates but does not invent conflicts
     * for disjoint windows.
     * Trigger: two students (same account) POST non-overlapping ranges at the same barrier.
     * Failure: lock wait mapped to 409, or conflict query used the wrong interval comparison.
     */
    @Test
    void concurrentNonOverlappingCreatesOnSameEquipmentBothSucceed() throws Exception {
        Long equipmentId = createEquipment("disjoint");
        Instant morning = Instant.now().plus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant afternoon = morning.plus(3, ChronoUnit.HOURS);
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return createStatus(equipmentId, morning, morning.plus(1, ChronoUnit.HOURS), student);
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return createStatus(equipmentId, afternoon, afternoon.plus(1, ChronoUnit.HOURS), student);
            });

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactly(201, 201);
        }
    }

    /**
     * Invariant: N concurrent identical overlapping creates yield exactly one 201.
     * Trigger: three POSTs of the same window after a single CyclicBarrier.
     * Failure: create lock does not cover the insert transaction, or conflict check
     * uses a snapshot from before the winner committed.
     */
    @Test
    void threeConcurrentIdenticalCreatesYieldSingleSuccess() throws Exception {
        Long equipmentId = createEquipment("triple");
        Instant startTime = Instant.now().plus(9, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String body = reservationJson(equipmentId, startTime, startTime.plus(2, ChronoUnit.HOURS), "三路并发");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier start = new CyclicBarrier(3);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<Integer>> results = List.of(
                    executor.submit(() -> createRaw(body, student, start)),
                    executor.submit(() -> createRaw(body, student, start)),
                    executor.submit(() -> createRaw(body, student, start)));
            List<Integer> statuses = List.of(
                    results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS),
                    results.get(2).get(10, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(2);
        }
    }

    /**
     * Invariant: REJECTED / EXPIRED / CANCELLED are not conflicting statuses, so the
     * slot can be booked again. COMPLETED/PENDING cannot be re-approved.
     * Trigger: sequential legal then illegal transitions on one row, then a new create
     * on the freed slot.
     * Failure: {@code CONFLICTING_STATUSES} still includes REJECTED/EXPIRED/CANCELLED,
     * or {@code decide}/{@code complete}/{@code cancel} skip the current-status guard.
     */
    @Test
    void illegalTransitionsAreRejectedAndFreedSlotsAreReusable() throws Exception {
        Long equipmentId = createEquipment("transitions");
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Long rejectedId = createHttpReservationOn(equipmentId, start, "将被拒绝");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");

        mockMvc.perform(patch("/api/reservations/{id}/complete", rejectedId)
                        .header(HttpHeaders.AUTHORIZATION, teacher))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_COMPLETABLE"));

        mockMvc.perform(patch("/api/reservations/{id}/decision", rejectedId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(patch("/api/reservations/{id}/decision", rejectedId)
                        .header(HttpHeaders.AUTHORIZATION, teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_PENDING"));

        mockMvc.perform(patch("/api/reservations/{id}/cancel", rejectedId)
                        .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"));

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(equipmentId, start, start.plus(1, ChronoUnit.HOURS), "拒绝后重订")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private String mutateSharedVersion(Long reservationId, CyclicBarrier loaded,
                                       Consumer<Reservation> mutation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
                try {
                    loaded.await(5, TimeUnit.SECONDS);
                } catch (Exception barrierError) {
                    throw new IllegalStateException("both writers must load the same version", barrierError);
                }
                mutation.accept(reservation);
                reservationRepository.flush();
            });
            return "ok";
        } catch (RuntimeException exception) {
            if (isOptimisticLock(exception)) {
                return "conflict";
            }
            throw exception;
        }
    }

    private static boolean isOptimisticLock(Throwable error) {
        while (error != null) {
            if (error instanceof OptimisticLockingFailureException
                    || error instanceof OptimisticLockException) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    private <T> T getOrTimeout(Future<T> future, String race) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("Deadlock or hang suspected (" + race + "): future did not complete in 10s.", timeout);
        }
    }

    private static void shutdownRace(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker threads did not terminate; possible database deadlock");
        }
    }

    private int decideStatus(Long reservationId, String decision, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"" + decision + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int cancelStatus(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/cancel", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int completeStatus(Long reservationId, String bearer) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/complete", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int createStatus(Long equipmentId, Instant start, Instant end, String bearer) throws Exception {
        return createRaw(reservationJson(equipmentId, start, end, "非重叠并发"), bearer, null);
    }

    private int createRaw(String body, String bearer, CyclicBarrier start) throws Exception {
        if (start != null) {
            start.await();
        }
        return mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Long createHttpReservation(String purpose) throws Exception {
        Long equipmentId = createEquipment(purpose);
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        return createHttpReservationOn(equipmentId, start, purpose);
    }

    private Long createHttpReservationOn(Long equipmentId, Instant start, String purpose) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationJson(equipmentId, start, start.plus(1, ChronoUnit.HOURS), purpose)))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createEquipment(String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SM-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "状态机测试-" + label,
                                "category", "并发测试",
                                "location", "实验楼 S101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Equipment newEquipment(String label) {
        return equipmentRepository.saveAndFlush(new Equipment(
                "SM-" + UUID.randomUUID().toString().substring(0, 8),
                "状态机测试-" + label,
                "并发测试",
                "实验楼 S102"));
    }

    private Reservation pending(Equipment equipment, Instant start, Instant end, String purpose) {
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        return reservationRepository.saveAndFlush(new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                purpose,
                start,
                end,
                Instant.now().plus(15, ChronoUnit.MINUTES)));
    }

    private String reservationJson(Long equipmentId, Instant start, Instant end, String purpose) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "equipmentId", equipmentId,
                "purpose", purpose,
                "startTime", start.toString(),
                "endTime", end.toString()));
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
