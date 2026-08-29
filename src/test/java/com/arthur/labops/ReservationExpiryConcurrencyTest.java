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

import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.audit.OperationLog;
import com.arthur.labops.audit.OperationLogRepository;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.expiry.RabbitExpiryTopologyProperties;
import com.arthur.labops.reservation.expiry.RabbitReservationExpiryListener;
import com.arthur.labops.reservation.expiry.ReservationDeadlineHandler;
import com.arthur.labops.reservation.expiry.ReservationExpirationService;
import com.arthur.labops.reservation.expiry.ReservationExpiryCompensationJob;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Delay-queue compensation and {@code expireIfPending} races.
 *
 * <p>CI default (H2 + local lock + local scheduler): these tests call
 * {@link ReservationExpiryCompensationJob}, {@link ReservationExpirationService},
 * and a constructed {@link RabbitReservationExpiryListener} directly. They do not
 * wait on {@code TaskScheduler} and do not need a broker.
 *
 * <p>Real Rabbit HOL ordering stays in
 * {@link com.arthur.labops.reservation.expiry.RabbitReservationExpiryOrderingIntegrationTest},
 * which skips unless {@code 127.0.0.1:5672} is open. Lock order
 * {@code Equipment → Reservation} for compensation is owned by
 * {@link ReservationLockOrderIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationExpiryConcurrencyTest {

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
    private OperationLogRepository operationLogRepository;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private ReservationDeadlineHandler deadlineHandler;

    @Autowired
    private ReservationExpiryCompensationJob compensationJob;

    @Autowired
    private EquipmentStatusService equipmentStatusService;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * Invariant: compensation expires only overdue PENDING rows; APPROVED with a
     * stale {@code expiresAt} and future PENDING rows are left untouched.
     * Scanning twice is idempotent. Failure: {@code expireIfPending} ignores
     * status, or {@code findOverduePendingIds} drops {@code expiresAt <= now}.
     */
    @Test
    void compensationExpiresOnlyOverduePendingRowsAndIsIdempotent() {
        Reservation overduePendingRow = overduePending("job-pending");
        Reservation futurePendingRow = futurePending("job-future");
        Reservation approvedStaleExpiry = terminalWithPastExpiry("job-approved", Reservation::approve);

        compensationJob.expireOverduePendingReservations();
        compensationJob.expireOverduePendingReservations();

        assertThat(reservationRepository.findById(overduePendingRow.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(futurePendingRow.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(reservationRepository.findById(approvedStaleExpiry.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.APPROVED);
    }

    /**
     * Invariant: a late delay message (Rabbit listener / local scheduler / compensation)
     * must not expire APPROVED, CANCELLED, or COMPLETED.
     * Failure: {@code expireIfPending} checks only {@code expiresAt}.
     */
    @Test
    void staleExpiryDoesNotRevertApprovedCancelledOrCompleted() {
        Reservation approved = terminalWithPastExpiry("stale-approved", Reservation::approve);
        Reservation cancelled = terminalWithPastExpiry("stale-cancelled", Reservation::cancel);
        Reservation completed = terminalWithPastExpiry("stale-completed", reservation -> {
            reservation.approve();
            reservation.complete();
        });

        assertThat(expirationService.expireIfPending(approved.getId())).isFalse();
        assertThat(expirationService.expireIfPending(cancelled.getId())).isFalse();
        assertThat(expirationService.expireIfPending(completed.getId())).isFalse();
        assertThat(reservationRepository.findById(approved.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.APPROVED);
        assertThat(reservationRepository.findById(cancelled.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservationRepository.findById(completed.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMPLETED);
    }

    /**
     * Invariant: duplicate Rabbit consume of the same overdue id expires once
     * and writes a single system audit row. The listener is constructed here so
     * default CI (local expiry mode) still covers the consume path.
     */
    @Test
    void rabbitListenerDuplicateConsumeExpiresOnce() {
        Reservation overdue = overduePending("listener-dup");
        long auditsBefore = expiryAudits(overdue.getId());
        RabbitReservationExpiryListener listener = new RabbitReservationExpiryListener(
                deadlineHandler, new RabbitExpiryTopologyProperties());

        listener.expire(String.valueOf(overdue.getId()));
        listener.expire(String.valueOf(overdue.getId()));

        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expiryAudits(overdue.getId()) - auditsBefore).isEqualTo(1);
    }

    /**
     * Invariant: two concurrent expire attempts on the same overdue PENDING write
     * EXPIRED once and emit a single system audit row.
     * Trigger: two {@code REQUIRES_NEW} {@code expireIfPending} calls after a barrier.
     * Failure: missing row lock lets both see PENDING, or audit is recorded when
     * expire returns false.
     */
    @Test
    void concurrentExpireIfPendingIsIdempotentAndAuditsOnce() throws Exception {
        Reservation overdue = overduePending("double-expire");
        long auditsBefore = expiryAudits(overdue.getId());
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return expirationService.expireIfPending(overdue.getId());
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return expirationService.expireIfPending(overdue.getId());
            });
            assertThat(List.of(
                    getOrTimeout(first, "expire first"),
                    getOrTimeout(second, "expire second")))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            shutdownRace(executor);
        }

        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expiryAudits(overdue.getId()) - auditsBefore).isEqualTo(1);
    }

    /**
     * Invariant: an overdue PENDING is never APPROVED; teacher decide and
     * compensation agree on EXPIRED (HTTP 409).
     */
    @Test
    void concurrentApproveAndExpireOnOverdueNeverLeaveApproved() throws Exception {
        Reservation overdue = overduePending("approve-vs-expire");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> decision = executor.submit(() -> {
                start.await();
                return mockMvc.perform(patch("/api/reservations/{id}/decision", overdue.getId())
                                .header(HttpHeaders.AUTHORIZATION, teacher)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"APPROVED\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            Future<Boolean> expired = executor.submit(() -> {
                start.await();
                return expirationService.expireIfPending(overdue.getId());
            });
            int http = getOrTimeout(decision, "approve vs expire");
            getOrTimeout(expired, "approve vs expire");
            assertThat(http).isEqualTo(409);
        } finally {
            shutdownRace(executor);
        }

        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    /**
     * Invariant: cancel vs expire of an overdue PENDING ends in CANCELLED or
     * EXPIRED, never PENDING, and both writers must not succeed.
     */
    @Test
    void concurrentCancelAndExpireLeaveSingleTerminalStatus() throws Exception {
        Reservation overdue = overduePending("cancel-vs-expire");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> cancel = executor.submit(() -> {
                start.await();
                return mockMvc.perform(patch("/api/reservations/{id}/cancel", overdue.getId())
                                .header(HttpHeaders.AUTHORIZATION, student))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            Future<Boolean> expire = executor.submit(() -> {
                start.await();
                return expirationService.expireIfPending(overdue.getId());
            });
            int cancelStatus = getOrTimeout(cancel, "cancel vs expire");
            boolean didExpire = getOrTimeout(expire, "cancel vs expire");
            assertThat(cancelStatus == 200 || didExpire).isTrue();
            assertThat(cancelStatus == 200 && didExpire).isFalse();
        } finally {
            shutdownRace(executor);
        }

        assertThat(reservationRepository.findById(overdue.getId()).orElseThrow().getStatus())
                .isIn(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);
    }

    /**
     * Invariant: one {@code expireIfPending} failure must not stop later ids.
     * Each id is {@code REQUIRES_NEW}; the job catches per row.
     * Failure: an uncaught exception aborts the scan loop.
     */
    @Test
    void compensationContinuesAfterOneReservationFails() {
        Reservation first = overduePending("batch-ok-1");
        Reservation boom = overduePending("batch-boom");
        Reservation third = overduePending("batch-ok-2");

        ReservationExpirationService wrapping = new ReservationExpirationService(
                reservationRepository, equipmentRepository, equipmentStatusService, auditLogService) {
            @Override
            public boolean expireIfPending(Long reservationId) {
                if (reservationId.equals(boom.getId())) {
                    throw new IllegalStateException("simulated expire failure");
                }
                return expirationService.expireIfPending(reservationId);
            }
        };
        new ReservationExpiryCompensationJob(reservationRepository, wrapping, equipmentStatusService)
                .expireOverduePendingReservations();

        assertThat(reservationRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(boom.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING);
        assertThat(reservationRepository.findById(third.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    /**
     * Invariant: EXPIRED is not a conflicting status, so the window can be booked again.
     */
    @Test
    void expiredSlotCanBeBookedAgain() throws Exception {
        Equipment equipment = equipmentRepository.saveAndFlush(new Equipment(
                "EX-" + UUID.randomUUID().toString().substring(0, 8),
                "过期并发-reuse-slot",
                "并发测试",
                "实验楼 E101"));
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        Reservation overdue = reservationRepository.saveAndFlush(new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                "reuse-slot",
                start,
                end,
                Instant.now().minusSeconds(5)));

        assertThat(expirationService.expireIfPending(overdue.getId())).isTrue();

        mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipment.getId(),
                                "purpose", "过期后重订",
                                "startTime", start.toString(),
                                "endTime", end.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /**
     * Invariant: a delay message for a missing row is a no-op so the listener
     * does not fail the consume.
     */
    @Test
    void expireIfPendingUnknownIdReturnsFalse() {
        assertThat(expirationService.expireIfPending(Long.MAX_VALUE)).isFalse();
    }

    private Reservation overduePending(String label) {
        return saveReservation(label, Instant.now().minusSeconds(5));
    }

    private Reservation futurePending(String label) {
        return saveReservation(label, Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private Reservation terminalWithPastExpiry(String label, java.util.function.Consumer<Reservation> mutation) {
        Reservation reservation = saveReservation(label, Instant.now().minusSeconds(5));
        mutation.accept(reservation);
        return reservationRepository.saveAndFlush(reservation);
    }

    private Reservation saveReservation(String label, Instant expiresAt) {
        Equipment equipment = equipmentRepository.saveAndFlush(new Equipment(
                "EX-" + UUID.randomUUID().toString().substring(0, 8),
                "过期并发-" + label,
                "并发测试",
                "实验楼 E101"));
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        Instant start = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        return reservationRepository.saveAndFlush(new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                label,
                start,
                start.plus(1, ChronoUnit.HOURS),
                expiresAt));
    }

    private long expiryAudits(Long reservationId) {
        return operationLogRepository.findAll().stream()
                .filter(log -> "RESERVATION_EXPIRED".equals(log.getAction()))
                .filter(log -> reservationId.equals(log.getTargetId()))
                .map(OperationLog::getId)
                .count();
    }

    private static <T> T getOrTimeout(Future<T> future, String race) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("timeout (" + race + ")", timeout);
        }
    }

    private static void shutdownRace(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker threads did not terminate; possible database deadlock");
        }
    }
}
