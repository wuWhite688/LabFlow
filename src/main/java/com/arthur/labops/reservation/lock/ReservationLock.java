package com.arthur.labops.reservation.lock;

import java.util.function.Supplier;

public interface ReservationLock {

    <T> T execute(Long equipmentId, Supplier<T> action);

    /**
     * Per-user lock so quota checks cannot race across different equipment ids.
     * Always acquire user then equipment to keep a single lock order.
     */
    <T> T executeUser(Long userId, Supplier<T> action);
}
