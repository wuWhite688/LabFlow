package com.arthur.labops.reservation.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static RedisReservationLock newLock(RedisReservationLock.Commands commands) {
        return new RedisReservationLock(commands, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private static final class RecordingCommands implements RedisReservationLock.Commands {
        private final Supplier<Boolean> acquire;
        private final BiFunction<String, String, Long> unlock;
        private final List<UnlockCall> unlockCalls = new ArrayList<>();

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

        @Override
        public Boolean setIfAbsent(String key, String token, Duration leaseTime) {
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
