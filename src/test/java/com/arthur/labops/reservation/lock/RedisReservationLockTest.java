package com.arthur.labops.reservation.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class RedisReservationLockTest {

    @Test
    void acquisitionDataAccessExceptionMapsToRedisUnavailable() {
        RecordingCommands commands = RecordingCommands.failingAcquire(new QueryTimeoutException("redis timed out"));
        RedisReservationLock lock = newLock(commands);

        assertThatThrownBy(() -> lock.execute(7L, () -> "should-not-run"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("REDIS_OPERATION_FAILED");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(commands.unlockCalls).isEmpty();
    }

    @Test
    void acquisitionConnectionFailureMapsToRedisUnavailable() {
        RecordingCommands commands = RecordingCommands.failingAcquire(
                new RedisConnectionFailureException("connection refused"));
        RedisReservationLock lock = newLock(commands);

        assertThatThrownBy(() -> lock.execute(7L, () -> "should-not-run"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("REDIS_UNAVAILABLE");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(commands.unlockCalls).isEmpty();
    }

    @Test
    void actionDataAccessExceptionPropagatesUnchanged() {
        RecordingCommands commands = RecordingCommands.successful(1L);
        RedisReservationLock lock = newLock(commands);
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException("duplicate reservation");

        assertThatThrownBy(() -> lock.execute(7L, () -> {
            throw duplicate;
        })).isSameAs(duplicate);
        assertThat(commands.unlockCalls).hasSize(1);
    }

    @Test
    void unlockIsAttemptedAfterSuccessfulAcquisition() {
        RecordingCommands commands = RecordingCommands.successful(1L);
        RedisReservationLock lock = newLock(commands);
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThat(lock.execute(7L, () -> {
            ran.set(true);
            return "ok";
        })).isEqualTo("ok");
        assertThat(ran).isTrue();
        assertThat(commands.unlockCalls).hasSize(1);
        assertThat(commands.unlockCalls.get(0).key).startsWith("labops:reservation:equipment:7");
    }

    @Test
    void unlockZeroResultIsNotLoggedAsSuccessfulRelease() {
        RecordingCommands commands = RecordingCommands.successful(0L);
        RedisReservationLock lock = newLock(commands);
        Logger logger = (Logger) LoggerFactory.getLogger(RedisReservationLock.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(lock.execute(7L, () -> "kept")).isEqualTo("kept");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("not released (expired or not owned)")
                        && event.getFormattedMessage().contains("result=0")
                        && event.getLevel() == Level.WARN);
        assertThat(appender.list)
                .noneMatch(event -> event.getFormattedMessage().contains("lock released")
                        && event.getLevel() == Level.INFO);
        assertThat(commands.unlockCalls).hasSize(1);
    }

    @Test
    void unlockStillRunsWhenActionThrowsDataAccessException() {
        RecordingCommands commands = RecordingCommands.successful(1L);
        RedisReservationLock lock = newLock(commands);

        assertThatThrownBy(() -> lock.execute(7L, () -> {
            throw new QueryTimeoutException("jpa timeout");
        })).isInstanceOf(DataAccessException.class);
        assertThat(commands.unlockCalls).hasSize(1);
    }

    /**
     * Invariant: SET NX that stays false until wait expires surfaces
     * {@code RESERVATION_LOCK_TIMEOUT} 409 and must not DEL a key it never owned.
     * This does <em>not</em> fall through to {@code PESSIMISTIC_WRITE}; the client
     * retries. DB still serializes writers that actually enter {@code create}.
     * CI: in-memory Commands, no Redis.
     */
    @Test
    void waitLoopTimesOutWithoutUnlockingWhenKeyStaysTaken() {
        RecordingCommands commands = RecordingCommands.alwaysBusy();
        RedisReservationLock lock = new RedisReservationLock(
                commands, Duration.ofMillis(80), Duration.ofSeconds(10));

        assertThatThrownBy(() -> lock.execute(7L, () -> "should-not-run"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("RESERVATION_LOCK_TIMEOUT");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(commands.acquireCalls.get()).isGreaterThan(1);
        assertThat(commands.unlockCalls).isEmpty();
        assertThat(commands.leases).allMatch(lease -> lease.equals(Duration.ofSeconds(10)));
    }

    /**
     * Invariant: a contended SET NX is retried until it succeeds, then the action
     * runs once and unlock uses the same token that won the lock.
     * CI: in-memory Commands, no Redis.
     */
    @Test
    void retriesSetIfAbsentUntilAcquiredThenUnlocksWithSameToken() {
        RecordingCommands commands = RecordingCommands.failUntil(2);
        RedisReservationLock lock = new RedisReservationLock(
                commands, Duration.ofSeconds(2), Duration.ofMillis(250));

        assertThat(lock.execute(11L, () -> "booked")).isEqualTo("booked");
        assertThat(commands.acquireCalls.get()).isEqualTo(3);
        assertThat(commands.unlockCalls).hasSize(1);
        assertThat(commands.unlockCalls.get(0).token()).isEqualTo(commands.acquireTokens.get(2));
        assertThat(commands.leases).allMatch(lease -> lease.equals(Duration.ofMillis(250)));
    }

    /**
     * Invariant: a successful critical section is not rewritten into a Redis 503
     * when Lua DEL fails; the lease is left to expire. PESSIMISTIC_WRITE already
     * committed (or rolled back) independently.
     * CI: in-memory Commands, no Redis.
     */
    @Test
    void unlockDataAccessExceptionDoesNotHideSuccessfulAction() {
        RecordingCommands commands = RecordingCommands.unlockThrows(new QueryTimeoutException("lua timeout"));
        RedisReservationLock lock = newLock(commands);

        assertThat(lock.execute(7L, () -> "committed")).isEqualTo("committed");
        assertThat(commands.unlockCalls).hasSize(1);
    }

    /**
     * Invariant: interrupting the waiter while it is spinning aborts with
     * {@code RESERVATION_INTERRUPTED} and does not DEL.
     * CI: in-memory Commands, no Redis.
     */
    @Test
    void interruptDuringLockWaitMapsToReservationInterrupted() throws Exception {
        RecordingCommands commands = RecordingCommands.alwaysBusy();
        RedisReservationLock lock = new RedisReservationLock(
                commands, Duration.ofSeconds(5), Duration.ofSeconds(10));
        CountDownLatch enteredAcquire = new CountDownLatch(1);
        commands.onAcquire = enteredAcquire::countDown;
        AtomicReference<BusinessException> thrown = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            try {
                lock.execute(7L, () -> "should-not-run");
            } catch (BusinessException exception) {
                thrown.set(exception);
            }
        });
        waiter.start();
        assertThat(enteredAcquire.await(2, TimeUnit.SECONDS)).isTrue();
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(waiter.isAlive()).isFalse();
        assertThat(thrown.get()).isNotNull();
        assertThat(thrown.get().getCode()).isEqualTo("RESERVATION_INTERRUPTED");
        assertThat(thrown.get().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(commands.unlockCalls).isEmpty();
    }

    /**
     * Invariant: compare-and-delete Lua must GET the token before DEL so a
     * stolen or expired key is not deleted.
     * CI: script constant, no Redis.
     */
    @Test
    void unlockScriptComparesTokenBeforeDelete() {
        assertThat(RedisReservationLock.UNLOCK_SCRIPT.getScriptAsString())
                .contains("redis.call('get', KEYS[1]) == ARGV[1]")
                .contains("redis.call('del', KEYS[1])");
    }

    private static RedisReservationLock newLock(RedisReservationLock.Commands commands) {
        return new RedisReservationLock(commands, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private static final class RecordingCommands implements RedisReservationLock.Commands {
        private final Supplier<Boolean> acquire;
        private final BiFunction<String, String, Long> unlock;
        private final List<UnlockCall> unlockCalls = new ArrayList<>();
        private final List<Duration> leases = new ArrayList<>();
        private final List<String> acquireTokens = new ArrayList<>();
        private final AtomicInteger acquireCalls = new AtomicInteger();
        private volatile Runnable onAcquire = () -> {
        };

        private RecordingCommands(Supplier<Boolean> acquire, BiFunction<String, String, Long> unlock) {
            this.acquire = acquire;
            this.unlock = unlock;
        }

        static RecordingCommands successful(Long unlockResult) {
            return new RecordingCommands(() -> true, (key, token) -> unlockResult);
        }

        static RecordingCommands failingAcquire(RuntimeException error) {
            return new RecordingCommands(() -> {
                throw error;
            }, (key, token) -> 1L);
        }

        static RecordingCommands alwaysBusy() {
            return new RecordingCommands(() -> false, (key, token) -> 1L);
        }

        static RecordingCommands failUntil(int failures) {
            AtomicInteger remaining = new AtomicInteger(failures);
            return new RecordingCommands(() -> remaining.getAndDecrement() <= 0, (key, token) -> 1L);
        }

        static RecordingCommands unlockThrows(RuntimeException error) {
            return new RecordingCommands(() -> true, (key, token) -> {
                throw error;
            });
        }

        @Override
        public Boolean setIfAbsent(String key, String token, Duration leaseTime) {
            acquireCalls.incrementAndGet();
            acquireTokens.add(token);
            leases.add(leaseTime);
            onAcquire.run();
            return acquire.get();
        }

        @Override
        public Long unlock(String key, String token) {
            unlockCalls.add(new UnlockCall(key, token));
            return unlock.apply(key, token);
        }
    }

    private record UnlockCall(String key, String token) {
    }
}
