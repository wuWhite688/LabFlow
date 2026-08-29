package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentRequestStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An outbound intent can stop being true while it waits to be retried.
 *
 * <p>Retry made the outbound side reliable. Reliable is not the same as correct:
 * a payment request whose reservation has since expired must not be sent, and a
 * retry loop that only asks "did the channel accept this yet?" will happily
 * charge for a slot the platform has already given to somebody else.
 *
 * <p>The compensating refund does eventually return the money, but that is a
 * repair, not an excuse — the platform should not be taking money it knows it
 * will have to give straight back.
 *
 * <p>Same shape as the reservation deadlines: drop the intent when it settles,
 * <em>and</em> guard on the firing path, because the drop is cleanup and the
 * guard is what makes a late attempt safe.
 */
@SpringBootTest(properties = {
        "labops.payment.window=500ms",
        "labops.payment.outbound.retry-interval=200ms",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/intent-expiry"
})
@AutoConfigureMockMvc
class PaymentIntentExpiryIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimulatedPaymentChannel channel;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private PaymentRequestRepository requestRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void aPaymentIntentIsAbandonedWhenItsWindowClosesInsteadOfBeingRetried() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("INTENT-EXP", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        // The channel is down for the first attempt, so the request is left FAILED
        // and eligible for retry.
        channel.failNextOutbound(1);
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);

        // While it waits, the payment window elapses and the slot goes back.
        awaitReservation(reservationId, ReservationStatus.EXPIRED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.CLOSED);

        String payKey = PaymentIdempotency.payment(orderNo);
        Await.until("the payment intent to be abandoned", Duration.ofSeconds(30),
                () -> requestRepository.findByIdempotencyKey(payKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.OBSOLETE)
                        .isPresent());

        // Well past the retry backoff. Nothing must have been charged.
        TimeUnit.SECONDS.sleep(8);

        assertThat(channel.ledger())
                .as("the platform must not charge for a reservation it has already given away")
                .noneMatch(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.PAYMENT);
        assertThat(channel.discardPending())
                .as("and therefore no callback should be waiting either")
                .isZero();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .as("no charge means nothing to refund, so the order stays closed")
                .isEqualTo(PaymentOrderStatus.CLOSED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    private void awaitReservation(Long reservationId, ReservationStatus expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (reservationRepository.findById(reservationId).orElseThrow().getStatus() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(expected);
    }
}
