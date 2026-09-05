package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.arthur.labops.payment.*;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.ReservationPaymentTimeoutService;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Real InnoDB schedules, not a repeated simultaneous-start lottery. The test-only
 * repository decorator pauses AFTER the real query, before service guards/mutations.
 * A root observer checks the waiter's exact connection in innodb_trx; no sleep is
 * used as evidence of blocking. Every wait is bounded and gates open in finally.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "labops.demo-users.enabled=true", "labops.demo-data.enabled=false",
        "labops.reservation-lock.mode=local", "labops.reservation-expiry.mode=local",
        "labops.reservation-expiry.scan-interval=3600000",
        "labops.payment.outbound.retry-interval=1h",
        "labops.payment.window=10m", "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/request-mysql",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "management.health.redis.enabled=false", "management.health.rabbit.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=8"
})
@AutoConfigureMockMvc
@Import(PaymentRequestMysqlConcurrencyTest.ControlledQueries.class)
@Timeout(60)
class PaymentRequestMysqlConcurrencyTest {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withPassword("payment_test_password");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PaymentRequestRepository requests;
    @Autowired PaymentRequestStateService states;
    @Autowired PaymentCallbackService callbacks;
    @Autowired PaymentDispatchService dispatch;
    @Autowired PaymentOrderRepository orders;
    @Autowired SimulatedPaymentChannel channel;
    @Autowired ReservationPaymentTimeoutService timeouts;
    @Autowired QueryControl control;

    private ExecutorService executor;
    private PaymentScenario scenario;
    private String orderNo;
    private String key;
    private Long reservationId;
    private Long equipmentId;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        control.jdbc = jdbc;
        TestAuth.clearCache();
        channel.reset();
        assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        scenario = new PaymentScenario(mvc, mapper);
        equipmentId = scenario.createPricedEquipment("REQ-MYSQL", 6000);
        reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        orderNo = PaymentService.orderNoFor(reservationId);
        key = PaymentIdempotency.payment(orderNo);
        // No event: tests decide exactly when a physical channel attempt starts.
        requests.saveAndFlush(new PaymentRequest(orderNo, key, PaymentTransactionType.PAYMENT, 6000));
    }

    @AfterEach
    void stopWorkers() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(12, TimeUnit.SECONDS))
                .as("workers must finish before the next test uses the database").isTrue();
    }

    @Test
    void unlockedNegativeControlDeterministicallyReproducesAttemptRollback() throws Exception {
        Gate oldRead = new Gate("findByIdempotencyKey", key, true);
        Future<Void> old = submit(oldRead, () -> inTransaction(() -> {
            // Deliberately reproduce the pre-fix algorithm, not the fixed service.
            PaymentRequest stale = requests.findByIdempotencyKey(key).orElseThrow();
            assertThat(stale.matchesChannelKey(key)).isTrue();
            stale.markSent();
            return null;
        }));
        try {
            reached(oldRead);
            callbacks.handle(rejection(key));
            assertReopened();
        } finally {
            oldRead.release.countDown();
        }
        done(old);
        PaymentRequest result = current();
        assertThat(result.getChannelAttempt()).as("negative control: stale full-row UPDATE rolls #1 back").isZero();
        assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.SENT);
        assertThat(requests.findDueKeys(Instant.now())).doesNotContain(key);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectionFirstMakesOldCompletionWaitThenNoOp(boolean sent) throws Exception {
        Gate rejection = new Gate("findByOrderNoForUpdateOrderByIdAsc", orderNo, true);
        Gate completion = new Gate("findByIdempotencyKeyForUpdate", key, false);
        Future<PaymentCallbackResult> first = submit(rejection, () -> callbacks.handle(rejection(key)));
        Future<Object> second;
        try {
            reached(rejection);
            second = submit(completion, () -> complete(sent));
            assertLockWait(completion, second);
        } finally {
            rejection.release.countDown();
        }
        done(first);
        assertThat(done(second)).isEqualTo(sent ? Boolean.FALSE : null);
        assertReopened();
        // Verify recovery really sends #1 and records money, rather than merely
        // leaving a row that looks eligible for the scanner.
        dispatch.attempt(key);
        assertThat(current().getChannelAttempt()).isEqualTo(1);
        assertThat(current().getStatus()).isEqualTo(PaymentRequestStatus.SENT);
        assertThat(orders.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.PAID);
        assertThat(channel.ledger()).filteredOn(e -> e.orderNo().equals(orderNo)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionFirstSerializesRejectionWithoutDoubleChargingBudget(boolean sent) throws Exception {
        Gate completion = new Gate("findByIdempotencyKeyForUpdate", key, true);
        Gate rejection = new Gate("findByOrderNoForUpdateOrderByIdAsc", orderNo, false);
        Future<Object> first = submit(completion, () -> complete(sent));
        Future<PaymentCallbackResult> second;
        try {
            reached(completion);
            second = submit(rejection, () -> callbacks.handle(rejection(key)));
            assertLockWait(rejection, second);
        } finally {
            // Never wait for the callback to commit while completion owns its row.
            completion.release.countDown();
        }
        done(first);
        done(second);
        assertReopened();
    }

    @Test
    void duplicateRejectionsAdvanceOnlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<PaymentCallbackResult> reject = () -> {
            require(start, "duplicate callback start");
            return callbacks.handle(rejection(key));
        };
        Future<PaymentCallbackResult> a = executor.submit(reject);
        Future<PaymentCallbackResult> b = executor.submit(reject);
        start.countDown();
        done(a);
        done(b);
        assertReopened();
    }

    @Test
    void obsoleteIntentCannotBeRevivedByWaitingCompletionOrCallback() throws Exception {
        Gate obsolete = new Gate("findByIdempotencyKeyForUpdate", key, true);
        Gate completion = new Gate("findByIdempotencyKeyForUpdate", key, false);
        Future<Void> first = submit(obsolete, () -> { dispatch.abandonIntent(key); return null; });
        Future<Boolean> second;
        try {
            reached(obsolete);
            second = submit(completion, () -> states.markSent(key, key));
            assertLockWait(completion, second);
        } finally {
            obsolete.release.countDown();
        }
        done(first);
        assertThat(done(second)).isFalse();
        callbacks.handle(rejection(key));
        assertThat(states.markFailed(key, key, "late transport failure")).isNull();
        assertThat(current().getStatus()).isEqualTo(PaymentRequestStatus.OBSOLETE);
        assertThat(current().getChannelAttempt()).isZero();
        assertThat(requests.findDueKeys(Instant.now())).doesNotContain(key);
    }

    @Test
    void concurrentFirstEnqueueCreatesOneIntent() throws Exception {
        requests.deleteById(current().getId());
        Gate creation = new Gate("findByIdempotencyKeyForUpdate", key, true);
        // Gate sees an absent request while the first transaction owns the order.
        Future<Boolean> first = submit(creation, () -> dispatch.enqueue(orderNo, PaymentTransactionType.PAYMENT, 6000, key));
        Future<Boolean> second;
        Gate orderWait = new Gate("findByOrderNoForUpdate", orderNo, false);
        try {
            reached(creation);
            second = submit(orderWait, () -> dispatch.enqueue(orderNo, PaymentTransactionType.PAYMENT, 6000, key));
            assertLockWait(orderWait, second);
        } finally {
            creation.release.countDown();
        }
        assertThat(List.of(done(first), done(second))).containsExactlyInAnyOrder(true, false);
        assertThat(requests.findByOrderNoOrderByIdAsc(orderNo)).hasSize(1);
    }

    @Test
    void synchronousRejectionReturnsAndFreshAttemptSucceeds() throws Exception {
        channel.rejectNextOutboundFinal(1);
        done(executor.submit(() -> dispatch.attempt(key)));
        assertReopened("CH-P-");
        dispatch.attempt(key);
        assertThat(current().getChannelAttempt()).isEqualTo(1);
        assertThat(current().getStatus()).isEqualTo(PaymentRequestStatus.SENT);
        assertThat(channel.ledger()).filteredOn(e -> e.orderNo().equals(orderNo)).hasSize(1);
    }

    @Test
    void exhaustedCallbackCommitsOneTicketWithoutNestedTransactionSelfBlock() throws Exception {
        jdbc.update("update payment_requests set attempts=7 where idempotency_key=?", key);
        done(executor.submit(() -> callbacks.handle(rejection(key))));
        PaymentRequest result = current();
        assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.ABANDONED);
        assertThat(result.getAttempts()).isEqualTo(8);
        assertThat(result.getChannelAttempt()).isEqualTo(1);
        callbacks.handle(rejection(key));
        assertThat(states.markSent(key, key)).isFalse();
        assertThat(states.markFailed(key, key, "late")).isNull();
        assertThat(current().getStatus()).isEqualTo(PaymentRequestStatus.ABANDONED);
        assertThat(jdbc.queryForObject("select count(*) from fault_work_orders where discrepancy_key=?",
                Long.class, "outbound|" + key)).isEqualTo(1);
        assertThat(requests.findDueKeys(Instant.now())).doesNotContain(key);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closureAndCallbackRespectTheSameLockOrder(boolean cancellation) throws Exception {
        if (!cancellation) {
            jdbc.update("update equipment_reservations set payment_deadline=? where id=?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(60)), reservationId);
        }
        Gate rejection = new Gate("findByOrderNoForUpdateOrderByIdAsc", orderNo, true);
        Gate closure = new Gate("findByIdForUpdate", equipmentId, false);
        Future<PaymentCallbackResult> first = submit(rejection, () -> callbacks.handle(rejection(key)));
        Future<?> second;
        try {
            reached(rejection);
            second = submit(closure, () -> cancellation
                    ? scenario.cancelAsStudent(reservationId) : timeouts.closeIfPaymentWindowElapsed(reservationId));
            assertLockWait(closure, second);
        } finally {
            rejection.release.countDown();
        }
        done(first);
        done(second);
        assertThat(current().getStatus()).isEqualTo(PaymentRequestStatus.OBSOLETE);
        assertThat(current().getChannelAttempt()).isEqualTo(1);
        assertThat(orders.findByOrderNo(orderNo).orElseThrow().getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
    }

    private Object complete(boolean sent) {
        return sent ? states.markSent(key, key) : states.markFailed(key, key, "old transport failure");
    }

    private PaymentCallbackRequest rejection(String channelKey) {
        return new PaymentCallbackRequest(orderNo, channelKey, PaymentTransactionType.PAYMENT,
                6000L, "REJECT-" + orderNo, "FAILED", Instant.now());
    }

    private PaymentRequest current() { return requests.findByIdempotencyKey(key).orElseThrow(); }

    private void assertReopened() {
        assertReopened("REJECT-" + orderNo);
    }

    private void assertReopened(String channelTransactionPrefix) {
        PaymentRequest result = current();
        assertThat(result.getChannelAttempt()).isEqualTo(1);
        assertThat(result.channelKey()).isEqualTo(key + "#1");
        assertThat(result.getStatus()).isEqualTo(PaymentRequestStatus.FAILED);
        assertThat(result.getAttempts()).isEqualTo(1);
        assertThat(result.getLastError()).startsWith("渠道回调状态 FAILED，交易号 " + channelTransactionPrefix);
        assertThat(result.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(requests.findDueKeys(Instant.now())).contains(key);
    }

    private <T> T inTransaction(Callable<T> body) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            try { return body.call(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private <T> Future<T> submit(Gate gate, Callable<T> body) {
        return executor.submit(() -> {
            control.lane.set(gate);
            try { return body.call(); }
            finally { control.lane.remove(); }
        });
    }

    private static <T> T done(Future<T> task) throws Exception { return task.get(12, TimeUnit.SECONDS); }
    private static void reached(Gate gate) throws Exception { require(gate.queried, "query returned: " + gate.method); }
    private static void require(CountDownLatch latch, String stage) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out at " + stage);
    }

    private void assertLockWait(Gate gate, Future<?> waiter) throws Exception {
        require(gate.entered, "waiter entered: " + gate.method);
        // App credentials need not have PROCESS. Root is used ONLY by this observer.
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            await().alias("InnoDB LOCK WAIT for connection " + gate.connectionId + " / " + gate.method)
                    .pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(waiter.isDone()).as("waiter must not bypass the locked query").isFalse();
                        try (var stmt = connection.prepareStatement(
                                "select trx_state from information_schema.innodb_trx where trx_mysql_thread_id=?")) {
                            stmt.setLong(1, gate.connectionId);
                            try (var rows = stmt.executeQuery()) {
                                assertThat(rows.next()).as("waiter's InnoDB transaction must be visible").isTrue();
                                assertThat(rows.getString(1)).isEqualTo("LOCK WAIT");
                            }
                        }
                    });
        }
    }

    static final class Gate {
        final String method;
        final Object argument;
        final boolean pause;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch queried = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile long connectionId;
        Gate(String method, Object argument, boolean pause) {
            this.method = method; this.argument = argument; this.pause = pause;
        }
    }

    static final class QueryControl {
        final ThreadLocal<Gate> lane = new ThreadLocal<>();
        volatile JdbcTemplate jdbc;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledQueries {
        @Bean static QueryControl queryControl() { return new QueryControl(); }

        @Bean static BeanPostProcessor paymentTestDecorator(QueryControl control) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    Class<?> repositoryType = bean instanceof PaymentRequestRepository ? PaymentRequestRepository.class
                            : bean instanceof PaymentOrderRepository ? PaymentOrderRepository.class
                            : bean instanceof EquipmentRepository ? EquipmentRepository.class : null;
                    if (repositoryType != null) {
                        return Proxy.newProxyInstance(repositoryType.getClassLoader(),
                                new Class<?>[]{repositoryType}, (proxy, method, args) -> {
                                    Gate gate = control.lane.get();
                                    boolean selected = gate != null && gate.method.equals(method.getName())
                                            && args != null && args.length > 0 && gate.argument.equals(args[0]);
                                    if (selected) {
                                        gate.connectionId = control.jdbc.queryForObject("select connection_id()", Long.class);
                                        gate.entered.countDown();
                                    }
                                    Object result;
                                    try { result = method.invoke(bean, args); }
                                    catch (InvocationTargetException e) { throw e.getCause(); }
                                    if (selected) {
                                        gate.queried.countDown();
                                        if (gate.pause) require(gate.release, "release query: " + gate.method);
                                    }
                                    return result;
                                });
                    }
                    // Only automatic dispatch is disabled. Explicit attempt() and
                    // synchronous channel callbacks still run the production path.
                    if (bean instanceof PaymentRequestQueuedEventListener || bean instanceof PaymentRequestRetryJob) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.setProxyTargetClass(true);
                        factory.addAdvice((MethodInterceptor) invocation -> {
                            String method = invocation.getMethod().getName();
                            if (method.equals("onQueued") || method.equals("retryDueRequests")) return null;
                            return invocation.proceed();
                        });
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }
}
