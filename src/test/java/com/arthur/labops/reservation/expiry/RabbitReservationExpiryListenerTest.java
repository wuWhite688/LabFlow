package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RabbitReservationExpiryListenerTest {

    private RecordingExpirationService expirationService;
    private RabbitReservationExpiryListener listener;

    @BeforeEach
    void setUp() {
        expirationService = new RecordingExpirationService();
        listener = new RabbitReservationExpiryListener(
                expirationService, new RabbitExpiryTopologyProperties());
    }

    @Test
    void nonNumericPayloadIsIgnoredWithoutCallingExpire() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        assertThat(expirationService.ids).isEmpty();
    }

    @Test
    void numericPayloadStillCallsExpireIfPending() {
        listener.expire("42");
        assertThat(expirationService.ids).containsExactly(42L);
    }

    @Test
    void invalidPayloadDoesNotBlockLaterValidMessage() {
        assertThatCode(() -> listener.expire("not-a-number")).doesNotThrowAnyException();
        listener.expire("7");
        assertThat(expirationService.ids).containsExactly(7L);
    }

    private static final class RecordingExpirationService extends ReservationExpirationService {
        private final List<Long> ids = new ArrayList<>();

        private RecordingExpirationService() {
            super(null, null, null, null);
        }

        @Override
        public boolean expireIfPending(Long reservationId) {
            ids.add(reservationId);
            return true;
        }
    }
}
