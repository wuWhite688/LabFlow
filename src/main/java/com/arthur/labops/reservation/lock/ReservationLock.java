package com.arthur.labops.reservation.lock;

import java.util.function.Supplier;

public interface ReservationLock {

    <T> T execute(Long equipmentId, Supplier<T> action);
}
