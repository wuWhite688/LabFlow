package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentController;
import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentProperties;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The money left the user, and the callback arrived after the payment window had
 * already closed the reservation.
 *
 * <p>The reservation is right to stay closed — the slot was given back and
 * somebody else may already hold it. But the payment is real and has to be
 * recorded, and the platform now owes that money back. Silently folding it onto
 * the order as a normal payment leaves "reservation EXPIRED, order PAID, money
 * kept" — and reconciliation reports that as balanced, because the channel
 * collected and the ledger recorded, so both sides agree.
 */
@SpringBootTest(properties = {
        "labops.payment.window=500ms",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/late-payment"
})
@AutoConfigureMockMvc
class LatePaymentAfterWindowIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;
    private static final long CHARGED_CENTS = 12_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimulatedPaymentChannel channel;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentProperties paymentProperties;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentRequestRepository requestRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void paymentArrivingAfterTheWindowIsRecordedAndRefunded() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-LATE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        // The user pays, but the callback is held up.
        scenario.payViaApi(orderNo, "student", "student123");
        Await.until("the charge to reach the channel", () -> channel.ledger().stream()
                .anyMatch(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.PAYMENT));
        awaitReservation(reservationId, ReservationStatus.EXPIRED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.CLOSED);

        // Only now does it land.
        assertThat(channel.deliverPending()).isEqualTo(1);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("a late payment must not resurrect a reservation whose slot was already released")
                .isEqualTo(ReservationStatus.EXPIRED);

        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getPaidCents())
                .as("the payment is real and must be on the books")
                .isEqualTo(CHARGED_CENTS);
        assertThat(order.getStatus())
                .as("collected against a closed reservation means we owe it back, not that the order is settled")
                .isEqualTo(PaymentOrderStatus.REFUND_DUE);

        Await.until("the compensating refund to be requested from the channel",
                () -> channel.ledger().stream()
                        .anyMatch(entry -> entry.orderNo().equals(orderNo)
                                && entry.type() == ChannelEntryType.REFUND));
        assertThat(channel.ledger())
                .as("a compensating refund must actually be requested from the channel")
                .filteredOn(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.REFUND)
                .hasSize(1);

        channel.deliverPending();

        PaymentOrder settled = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(settled.netCents())
                .as("the user must end up holding their money")
                .isZero();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    /**
     * CLOSED used to short-circuit amount coherence, so a one-cent late callback
     * was booked and immediately refunded. That is the same "one cent settles a
     * 6000-cent booking" hole, just after the window.
     */
    @Test
    void aLatePaymentForLessThanTheBilledAmountIsRejected() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-LATE-UNDER", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        scenario.payViaApi(orderNo, "student", "student123");
        Await.until("the charge to reach the channel", () -> channel.ledger().stream()
                .anyMatch(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.PAYMENT));
        awaitReservation(reservationId, ReservationStatus.EXPIRED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.CLOSED);

        mockMvc.perform(post("/api/payments/callback")
                        .header(PaymentController.CALLBACK_TOKEN_HEADER, paymentProperties.getCallbackToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderNo", orderNo,
                                "idempotencyKey", "CH-LATE-UNDER-1",
                                "type", "PAYMENT",
                                "amountCents", 1L,
                                "channelTxnId", "CH-LATE-UNDER-1",
                                "status", "SUCCESS",
                                "occurredAt", Instant.now().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getPaidCents()).isZero();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.CLOSED);
        assertThat(transactionRepository.countByOrderNo(orderNo)).isZero();
        assertThat(requestRepository.findByOrderNoOrderByIdAsc(orderNo))
                .filteredOn(request -> request.getType().name().equals("REFUND"))
                .isEmpty();
        assertThat(channel.ledger())
                .filteredOn(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.REFUND)
                .isEmpty();
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
