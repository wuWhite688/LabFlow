package com.arthur.labops.reservation.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.arthur.labops.common.BusinessException;

@Component
@ConditionalOnProperty(name = "labops.reservation-lock.mode", havingValue = "redis")
public class RedisReservationLock implements ReservationLock {

    private static final Logger log = LoggerFactory.getLogger(RedisReservationLock.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration waitTime;
    private final Duration leaseTime;

    public RedisReservationLock(
            StringRedisTemplate redisTemplate,
            @Value("${labops.reservation-lock.wait:2s}") Duration waitTime,
            @Value("${labops.reservation-lock.lease:10s}") Duration leaseTime) {
        this.redisTemplate = redisTemplate;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
    }

    @Override
    public <T> T execute(Long equipmentId, Supplier<T> action) {
        String key = "labops:reservation:equipment:" + equipmentId;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTime.toNanos();
        boolean acquired = false;
        try {
            do {
                acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, leaseTime));
                if (!acquired) {
                    pauseBriefly();
                }
            } while (!acquired && System.nanoTime() < deadline);

            if (!acquired) {
                throw new BusinessException(
                        "RESERVATION_LOCK_TIMEOUT",
                        "预约请求较多，请稍后重试",
                        HttpStatus.CONFLICT);
            }
            log.info("Redis reservation lock acquired key={} equipmentId={}", key, equipmentId);
            return action.get();
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(
                    "REDIS_UNAVAILABLE",
                    "预约锁服务暂时不可用",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    "REDIS_OPERATION_FAILED",
                    "预约锁操作失败",
                    HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired) {
                try {
                    redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
                    log.info("Redis reservation lock released key={} equipmentId={}", key, equipmentId);
                } catch (DataAccessException ignored) {
                    // 锁带有租约，释放失败时会自动过期，不能覆盖已经完成的业务结果。
                }
            }
        }
    }

    private void pauseBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    "RESERVATION_INTERRUPTED",
                    "预约请求被中断",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
