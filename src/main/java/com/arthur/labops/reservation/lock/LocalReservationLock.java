package com.arthur.labops.reservation.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-lock.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationLock implements ReservationLock {

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T execute(Long equipmentId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(equipmentId, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
