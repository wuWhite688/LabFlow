package com.arthur.labops.reservation.expiry;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "local", matchIfMissing = true)
public class LocalReservationExpiryScheduler implements ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalReservationExpiryScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ReservationDeadlineHandler deadlineHandler;

    /**
     * Armed deadlines, so a settled reservation can drop its task instead of
     * leaving it parked in the scheduler's queue until the original instant.
     * Keyed by kind + id (not by instant), so rescheduling replaces rather than
     * accumulates.
     */
    private final Map<String, ScheduledFuture<?>> armed = new ConcurrentHashMap<>();

    public LocalReservationExpiryScheduler(TaskScheduler taskScheduler,
                                           ReservationDeadlineHandler deadlineHandler) {
        this.taskScheduler = taskScheduler;
        this.deadlineHandler = deadlineHandler;
    }

    @Override
    public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        String key = key(kind, reservationId);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            armed.remove(key);
            deadlineHandler.fire(kind, reservationId);
        }, deadline);
        ScheduledFuture<?> previous = armed.put(key, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @Override
    public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        ScheduledFuture<?> future = armed.remove(key(kind, reservationId));
        if (future != null) {
            future.cancel(false);
            log.debug("Cancelled local reservation deadline kind={} reservationId={}", kind, reservationId);
        }
    }

    /** Visible for tests: how many deadlines are still parked in the scheduler. */
    public int armedCount() {
        return armed.size();
    }

    private static String key(ReservationDeadlineKind kind, Long reservationId) {
        return kind.name() + ':' + reservationId;
    }
}
