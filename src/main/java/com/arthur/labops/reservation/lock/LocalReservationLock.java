package com.arthur.labops.reservation.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-lock.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationLock implements ReservationLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T execute(Long equipmentId, Supplier<T> action) {
        return executeKey("equipment:" + equipmentId, action);
    }

    @Override
    public <T> T executeUser(Long userId, Supplier<T> action) {
        return executeKey("user:" + userId, action);
    }

    private <T> T executeKey(String key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
