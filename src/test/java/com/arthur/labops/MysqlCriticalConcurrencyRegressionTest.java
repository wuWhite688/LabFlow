package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.arthur.labops.payment.PaymentCallbackRequest;
import com.arthur.labops.payment.PaymentCallbackResult;
import com.arthur.labops.payment.PaymentCallbackService;
import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Regression coverage for failures that only occur on InnoDB REPEATABLE READ. */
@Testcontainers
@SpringBootTest(properties = {
        "labops.demo-users.enabled=true", "labops.demo-data.enabled=false",
        "labops.reservation-lock.mode=local", "labops.reservation-expiry.mode=local",
        "labops.reservation-expiry.scan-interval=3600000",
        "labops.payment.outbound.retry-interval=1h",
        "labops.payment.window=10m", "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/critical-mysql",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "management.health.redis.enabled=false", "management.health.rabbit.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=8"
})
@AutoConfigureMockMvc
@Import(MysqlCriticalConcurrencyRegressionTest.ControlledQueries.class)
@Timeout(90)
class MysqlCriticalConcurrencyRegressionTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withPassword("critical_concurrency_test_password");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentCallbackService callbacks;
    @Autowired PaymentOrderRepository orders;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired ReservationRepository reservations;
    @Autowired QueryControl control;

    private PaymentScenario paymentScenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        paymentScenario = new PaymentScenario(mvc, mapper);
        control.jdbc = jdbc;
        assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
    }

    @Test
    void overlappingApprovalsSerializeOnEquipmentAndObserveTheLatestCommit() throws Exception {
        Long equipmentId = createFreeEquipment();
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String student = bearer(mvc, mapper, "student", "student123");
        String teacher = bearer(mvc, mapper, "teacher", "teacher123");
        Long firstId = createReservation(equipmentId, start, student);
        Long secondId = createReservation(equipmentId, start.plus(1, ChronoUnit.MINUTES), student);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Gate firstOwnsEquipment = new Gate("findByIdForUpdate", equipmentId, true);
        Gate secondHasOldSnapshot = new Gate("findEquipmentIdById", secondId, true);
        try {
            Future<DecisionResult> first = submit(pool, firstOwnsEquipment, () -> decide(firstId, teacher));
            reached(firstOwnsEquipment);

            // This plain routing read deliberately creates the second transaction's
            // old RR snapshot while the first approval is still uncommitted.
            Future<DecisionResult> second = submit(pool, secondHasOldSnapshot, () -> decide(secondId, teacher));
            reached(secondHasOldSnapshot);
            secondHasOldSnapshot.release.countDown();
            assertLockWait(secondHasOldSnapshot, second);

            firstOwnsEquipment.release.countDown();
            List<DecisionResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results).extracting(DecisionResult::httpStatus).containsExactly(200, 409);
            assertThat(results.get(1).code()).isEqualTo("RESERVATION_CONFLICT");
        } finally {
            // Every pause is bounded, but opening both gates here also keeps a
            // failed assertion from stranding a database worker.
            firstOwnsEquipment.release.countDown();
            secondHasOldSnapshot.release.countDown();
            stop(pool);
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from equipment_reservations where equipment_id=? and status='APPROVED'",
                Long.class, equipmentId)).isEqualTo(1L);
    }

    @Test
    void identicalSuccessfulCallbacksAreAllAcknowledgedAndFoldMoneyOnce() throws Exception {
        Long equipmentId = paymentScenario.createPricedEquipment("MYSQL-CB", HOURLY_PRICE_CENTS);
        Long reservationId = paymentScenario.createReservation(equipmentId, 1);
        paymentScenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String idempotencyKey = PaymentIdempotency.payment(orderNo);
        PaymentCallbackRequest callback = new PaymentCallbackRequest(
                orderNo, idempotencyKey, PaymentTransactionType.PAYMENT,
                HOURLY_PRICE_CENTS, "MYSQL-DUP-" + reservationId,
                PaymentCallbackRequest.STATUS_SUCCESS, Instant.now());

        int deliveries = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        try {
            List<Future<PaymentCallbackResult>> attempts = new ArrayList<>();
            for (int index = 0; index < deliveries; index++) {
                attempts.add(pool.submit(() -> {
                    startGate.await();
                    return callbacks.handle(callback);
                }));
            }
            startGate.countDown();
            List<PaymentCallbackResult> results = getAll(attempts);
            assertThat(results).filteredOn(PaymentCallbackResult::accepted).hasSize(1);
            assertThat(results).allMatch(result -> result.orderStatus() == PaymentOrderStatus.PAID);
        } finally {
            stop(pool);
        }

        assertThat(transactions.countByOrderNo(orderNo)).isEqualTo(1L);
        assertThat(orders.findByOrderNo(orderNo).orElseThrow().getPaidCents())
                .isEqualTo(HOURLY_PRICE_CENTS);
        assertThat(reservations.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
    }

    @Test
    void firstCallbacksForDifferentOrdersNeitherDeadlockNorMisbill() throws Exception {
        // Two shapes at once. Orders on their own equipment share no equipment,
        // reservation or order row, so the only structure they contend on is the
        // payment_transactions idempotency index; orders on one shared equipment
        // additionally queue behind the same equipment row. The same-order tests
        // exercise neither shape, so a lock-order inversion between them would
        // pass those and only surface here.
        List<Long> reservationIds = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Long ownEquipment = paymentScenario.createPricedEquipment("MYSQL-XO-D", HOURLY_PRICE_CENTS);
            reservationIds.add(paymentScenario.createReservation(ownEquipment, 1));
        }
        Long sharedEquipment = paymentScenario.createPricedEquipment("MYSQL-XO-S", HOURLY_PRICE_CENTS);
        String student = bearer(mvc, mapper, "student", "student123");
        Instant slot = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        for (int index = 0; index < 4; index++) {
            // Three hours apart, two hours long: these must all reach
            // AWAITING_PAYMENT, so they are distinct payable orders rather than
            // the overlapping reservations the approval fix is supposed to reject.
            reservationIds.add(createReservation(sharedEquipment, slot.plus(index * 3L, ChronoUnit.HOURS), student));
        }

        List<PaymentCallbackRequest> deliveries = new ArrayList<>();
        Map<String, Long> expectedCents = new java.util.LinkedHashMap<>();
        for (Long reservationId : reservationIds) {
            paymentScenario.approve(reservationId);
            String orderNo = PaymentService.orderNoFor(reservationId);
            // Hours differ between the two shapes, so bill each order its own
            // amount instead of assuming one hourly charge.
            long amountCents = orders.findByOrderNo(orderNo).orElseThrow().getAmountCents();
            expectedCents.put(orderNo, amountCents);
            deliveries.add(new PaymentCallbackRequest(
                    orderNo, PaymentIdempotency.payment(orderNo), PaymentTransactionType.PAYMENT,
                    amountCents, "MYSQL-XO-" + reservationId,
                    PaymentCallbackRequest.STATUS_SUCCESS, Instant.now()));
        }

        LockCounters before = LockCounters.read();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(deliveries.size());
        List<PaymentCallbackResult> results;
        try {
            List<Future<PaymentCallbackResult>> attempts = new ArrayList<>();
            for (PaymentCallbackRequest delivery : deliveries) {
                attempts.add(pool.submit(() -> {
                    startGate.await();
                    return callbacks.handle(delivery);
                }));
            }
            startGate.countDown();
            // A deadlock surfaces here as the losing thread's exception rather
            // than as a quiet miscount, so collect before asserting anything.
            results = getAll(attempts);
        } finally {
            stop(pool);
        }

        LockCounters after = LockCounters.read();
        assertThat(after.deadlocks() - before.deadlocks())
                .as("InnoDB deadlocks rolled back during concurrent first callbacks").isZero();
        assertThat(after.timeouts() - before.timeouts())
                .as("InnoDB lock wait timeouts during concurrent first callbacks").isZero();

        assertThat(results).as("one result per concurrent first callback").hasSize(8);
        assertThat(results).allMatch(PaymentCallbackResult::accepted);
        assertThat(results).allMatch(result -> result.orderStatus() == PaymentOrderStatus.PAID);

        for (Long reservationId : reservationIds) {
            String orderNo = PaymentService.orderNoFor(reservationId);
            assertThat(transactions.countByOrderNo(orderNo)).as("ledger rows for " + orderNo).isEqualTo(1L);
            assertThat(orders.findByOrderNo(orderNo).orElseThrow().getPaidCents())
                    .as("paid cents for " + orderNo).isEqualTo(expectedCents.get(orderNo));
            assertThat(reservations.findById(reservationId).orElseThrow().getStatus())
                    .as("status of reservation " + reservationId).isEqualTo(ReservationStatus.PAID);
        }
    }

    /**
     * InnoDB rolls a deadlock back and the loser may be retried out of sight, so
     * "no exception reached the caller" is not on its own evidence that none
     * happened. These engine counters are.
     */
    private record LockCounters(long deadlocks, long timeouts) {
        static LockCounters read() throws Exception {
            // innodb_metrics needs the PROCESS privilege, which the pooled
            // application user does not have; borrow root the same way the
            // lock-wait probe above does.
            try (var connection = DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
                return new LockCounters(
                        counter(connection, "lock_deadlocks"), counter(connection, "lock_timeouts"));
            }
        }

        private static long counter(java.sql.Connection connection, String metric) throws Exception {
            try (var statement = connection.prepareStatement(
                    "select `count`, status from information_schema.innodb_metrics where name=?")) {
                statement.setString(1, metric);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).as("innodb_metrics row for " + metric).isTrue();
                    // Fail loudly rather than read a constant zero from a metric
                    // InnoDB was never sampling, which would leave this probe
                    // permanently green no matter what the callbacks did.
                    assertThat(rows.getString("status")).as("innodb_metrics " + metric).isEqualTo("enabled");
                    return rows.getLong("count");
                }
            }
        }
    }

    private Long createFreeEquipment() throws Exception {
        MvcResult result = mvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mvc, mapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "code", "MYSQL-OV-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "MySQL overlapping approval",
                                "category", "concurrency",
                                "location", "test lab"))))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result).get("id")).longValue();
    }

    private Long createReservation(Long equipmentId, Instant start, String authorization) throws Exception {
        MvcResult result = mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "MySQL overlap regression",
                                "startTime", start.toString(),
                                "endTime", start.plus(2, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) read(result).get("id")).longValue();
    }

    private DecisionResult decide(Long reservationId, String authorization) throws Exception {
        MvcResult result = mvc.perform(patch("/api/reservations/{id}/decision", reservationId)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andReturn();
        Map<String, Object> body = read(result);
        return new DecisionResult(result.getResponse().getStatus(), (String) body.get("code"));
    }

    private Map<String, Object> read(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
    }

    private static <T> List<T> getAll(List<Future<T>> futures) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private <T> Future<T> submit(ExecutorService pool, Gate gate, java.util.concurrent.Callable<T> task) {
        return pool.submit(() -> {
            control.lane.set(gate);
            try {
                return task.call();
            } finally {
                control.lane.remove();
            }
        });
    }

    private static void reached(Gate gate) throws InterruptedException {
        assertThat(gate.queried.await(10, TimeUnit.SECONDS))
                .as("controlled query returned: " + gate.method).isTrue();
    }

    private void assertLockWait(Gate gate, Future<?> waiter) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            await().pollInterval(java.time.Duration.ofMillis(25)).atMost(java.time.Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        assertThat(waiter.isDone()).isFalse();
                        try (var statement = connection.prepareStatement(
                                "select trx_state from information_schema.innodb_trx where trx_mysql_thread_id=?")) {
                            statement.setLong(1, gate.connectionId);
                            try (var rows = statement.executeQuery()) {
                                assertThat(rows.next()).isTrue();
                                assertThat(rows.getString(1)).isEqualTo("LOCK WAIT");
                            }
                        }
                    });
        }
    }

    private record DecisionResult(int httpStatus, String code) {
    }

    static final class Gate {
        final String method;
        final Object argument;
        final boolean pause;
        final CountDownLatch queried = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile long connectionId;

        Gate(String method, Object argument, boolean pause) {
            this.method = method;
            this.argument = argument;
            this.pause = pause;
        }
    }

    static final class QueryControl {
        final ThreadLocal<Gate> lane = new ThreadLocal<>();
        volatile JdbcTemplate jdbc;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledQueries {
        @Bean
        static QueryControl queryControl() {
            return new QueryControl();
        }

        @Bean
        static BeanPostProcessor mysqlConcurrencyRepositoryDecorator(QueryControl control) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    Class<?> repositoryType = bean instanceof ReservationRepository ? ReservationRepository.class
                            : bean instanceof EquipmentRepository ? EquipmentRepository.class : null;
                    if (repositoryType == null) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType},
                            (proxy, method, args) -> {
                                Gate gate = control.lane.get();
                                boolean selected = gate != null && gate.method.equals(method.getName())
                                        && args != null && args.length > 0 && gate.argument.equals(args[0]);
                                Object result;
                                try {
                                    result = method.invoke(bean, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                                if (selected) {
                                    gate.connectionId = control.jdbc.queryForObject("select connection_id()", Long.class);
                                    gate.queried.countDown();
                                    if (gate.pause && !gate.release.await(10, TimeUnit.SECONDS)) {
                                        throw new AssertionError("Timed out waiting to release " + gate.method);
                                    }
                                }
                                return result;
                            });
                }
            };
        }
    }
}
