package com.arthur.labops.reservation.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Default CI lock ({@code labops.reservation-lock.mode=local}). Same-equipment
 * creates are serialized here; overlapping HTTP creates are covered by
 * {@code ConcurrentReservationIntegrationTest}. Redis SET NX is unit-tested
 * separately and is not required for these assertions.
 */
class LocalReservationLockTest {

    @Test
    void sameEquipmentCriticalSectionsDoNotOverlap() throws Exception {
        LocalReservationLock lock = new LocalReservationLock();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> hold(lock, 9L, start, inside, maxInside));
            Future<?> second = executor.submit(() -> hold(lock, 9L, start, inside, maxInside));
            getOrTimeout(first, "same-equipment first");
            getOrTimeout(second, "same-equipment second");
        } finally {
            shutdownRace(executor);
        }

        assertThat(maxInside.get()).isEqualTo(1);
    }

    private static Void hold(LocalReservationLock lock, long equipmentId, CyclicBarrier start,
                             AtomicInteger inside, AtomicInteger maxInside) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return lock.execute(equipmentId, () -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            inside.decrementAndGet();
            return null;
        });
    }

    private static void getOrTimeout(Future<?> future, String race) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("timeout (" + race + ")", timeout);
        }
    }

    private static void shutdownRace(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker threads did not terminate");
        }
    }
}
