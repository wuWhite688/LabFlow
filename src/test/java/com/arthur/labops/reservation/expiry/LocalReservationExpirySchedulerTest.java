package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The local scheduler's bookkeeping. Before {@code cancel} existed, every decided
 * or cancelled reservation left its task parked in the scheduler's queue until
 * the original instant — harmless when it fired, but unbounded growth in the
 * meantime, and the payment window would have doubled the rate.
 */
class LocalReservationExpirySchedulerTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private RecordingDeadlineHandler deadlineHandler;
    private LocalReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        deadlineHandler = new RecordingDeadlineHandler();
        scheduler = new LocalReservationExpiryScheduler(taskScheduler, deadlineHandler);
    }

    @AfterEach
    void tearDown() {
        taskScheduler.shutdown();
    }

    @Test
    void cancelledDeadlineIsDroppedAndNeverFires() throws Exception {
        Instant deadline = Instant.now().plusMillis(300);
        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 1L, deadline);
        assertThat(scheduler.armedCount()).isEqualTo(1);

        scheduler.cancel(ReservationDeadlineKind.APPROVAL, 1L, deadline);
        assertThat(scheduler.armedCount())
                .as("a settled reservation must not keep a task parked in the scheduler")
                .isZero();

        TimeUnit.MILLISECONDS.sleep(600);
        assertThat(deadlineHandler.fired).isEmpty();
    }

    /**
     * A reservation can hold an approval deadline and later a payment deadline.
     * Cancelling one must leave the other armed, or approving a reservation would
     * silently disarm the payment window it just opened.
     */
    @Test
    void approvalAndPaymentDeadlinesAreTrackedIndependently() {
        Instant approvalDeadline = Instant.now().plus(Duration.ofMinutes(5));
        Instant paymentDeadline = Instant.now().plus(Duration.ofMinutes(10));

        scheduler.schedule(ReservationDeadlineKind.APPROVAL, 7L, approvalDeadline);
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 7L, paymentDeadline);
        assertThat(scheduler.armedCount()).isEqualTo(2);

        scheduler.cancel(ReservationDeadlineKind.APPROVAL, 7L, approvalDeadline);
        assertThat(scheduler.armedCount()).isEqualTo(1);

        scheduler.cancel(ReservationDeadlineKind.PAYMENT, 7L, paymentDeadline);
        assertThat(scheduler.armedCount()).isZero();
    }

    @Test
    void reschedulingReplacesRatherThanAccumulates() {
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 3L, Instant.now().plus(Duration.ofMinutes(5)));
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 3L, Instant.now().plus(Duration.ofMinutes(9)));

        assertThat(scheduler.armedCount()).isEqualTo(1);
    }

    @Test
    void firedDeadlineReleasesItsSlot() throws Exception {
        scheduler.schedule(ReservationDeadlineKind.PAYMENT, 5L, Instant.now().plusMillis(100));

        TimeUnit.MILLISECONDS.sleep(600);
        assertThat(deadlineHandler.fired)
                .containsExactly(Map.entry(ReservationDeadlineKind.PAYMENT, 5L));
        assertThat(scheduler.armedCount())
                .as("a deadline that already fired must not stay in the map either")
                .isZero();
    }

    private static final class RecordingDeadlineHandler extends ReservationDeadlineHandler {
        private final List<Map.Entry<ReservationDeadlineKind, Long>> fired = new CopyOnWriteArrayList<>();

        private RecordingDeadlineHandler() {
            super(null, null);
        }

        @Override
        public boolean fire(ReservationDeadlineKind kind, Long reservationId) {
            fired.add(Map.entry(kind, reservationId));
            return true;
        }
    }
}
