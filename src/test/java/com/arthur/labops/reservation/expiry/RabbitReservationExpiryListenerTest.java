package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RabbitReservationExpiryListenerTest {

    private RecordingDeadlineHandler deadlineHandler;
    private RabbitReservationExpiryListener listener;

    @BeforeEach
    void setUp() {
        deadlineHandler = new RecordingDeadlineHandler();
        listener = new RabbitReservationExpiryListener(
                deadlineHandler, new RabbitExpiryTopologyProperties());
    }

    @Test
    void nonNumericPayloadIsIgnoredWithoutCallingExpire() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        assertThat(deadlineHandler.fired).isEmpty();
    }

    @Test
    void numericPayloadStillCallsExpireIfPending() {
        listener.expire("42");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.APPROVAL, 42L));
    }

    /**
     * The payment window rides the same work queue. An untagged payload must keep
     * meaning "approval deadline" so messages written by the previous version
     * still decode, and a tagged one must not be mistaken for an approval.
     */
    @Test
    void taggedPayloadRoutesToThePaymentDeadline() {
        listener.expire("PAYMENT:9");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.PAYMENT, 9L));
    }

    @Test
    void unknownKindIsIgnored() {
        assertThatCode(() -> listener.expire("NONSENSE:9")).doesNotThrowAnyException();
        assertThat(deadlineHandler.fired).isEmpty();
    }

    @Test
    void invalidPayloadDoesNotBlockLaterValidMessage() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        listener.expire("7");
        assertThat(deadlineHandler.fired)
                .containsExactly(entry(ReservationDeadlineKind.APPROVAL, 7L));
    }

    private static Map.Entry<ReservationDeadlineKind, Long> entry(ReservationDeadlineKind kind, Long id) {
        return Map.entry(kind, id);
    }

    private static final class RecordingDeadlineHandler extends ReservationDeadlineHandler {
        private final List<Map.Entry<ReservationDeadlineKind, Long>> fired = new ArrayList<>();

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
