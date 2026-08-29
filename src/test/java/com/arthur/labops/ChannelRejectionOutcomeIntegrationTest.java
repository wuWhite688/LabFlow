package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.arthur.labops.payment.channel.ChannelEntry;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A terminal channel rejection is not a successful money movement with a FAILED
 * label painted on afterwards. It is a final outcome for one channel attempt and
 * must therefore do two things at once: write no money row, and reopen exactly
 * the attempt that received the rejection — never a newer one.
 */
@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/channel-rejection"
})
@AutoConfigureMockMvc
class ChannelRejectionOutcomeIntegrationTest {

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
    void aTerminallyRejectedRefundRetriesButMovesMoneyOnlyOnce() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-REF", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, HOURLY_PRICE_CENTS);
        channel.deliverPending();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);

        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);
        channel.rejectNextOutboundFinal(1);
        scenario.cancelAsStudent(reservationId);

        Await.until("the rejected refund attempt to be marked sent", () ->
                requestRepository.findByIdempotencyKey(refundKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                        .isPresent());
        assertThat(refundEntries(orderNo))
                .as("a terminal rejection is an outcome, not money")
                .isEmpty();

        channel.deliverPending();

        Await.until("a fresh refund attempt to reach the channel", () -> refundEntries(orderNo).size() == 1);
        assertThat(requestRepository.findByIdempotencyKey(refundKey).orElseThrow().getChannelAttempt())
                .isEqualTo(1);

        channel.deliverPending();

        assertThat(refundEntries(orderNo))
                .as("one rejected attempt plus one successful retry still means one real refund")
                .hasSize(1);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void aTerminallyRejectedPaymentRetriesButChargesOnlyOnce() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-PAY", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String payKey = PaymentIdempotency.payment(orderNo);

        channel.rejectNextOutboundFinal(1);
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);

        Await.until("the rejected payment attempt to be marked sent", () ->
                requestRepository.findByIdempotencyKey(payKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                        .isPresent());
        assertThat(paymentEntries(orderNo)).isEmpty();

        channel.deliverPending();
        Await.until("a fresh payment attempt to reach the channel", () -> paymentEntries(orderNo).size() == 1);
        channel.deliverPending();

        assertThat(paymentEntries(orderNo)).hasSize(1);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
    }

    @Test
    void anOldFailedCallbackCannotReopenANewerSentAttempt() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-STALE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String payKey = PaymentIdempotency.payment(orderNo);

        channel.rejectNextOutboundFinal(1);
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);
        Await.until("attempt zero to be sent", () ->
                requestRepository.findByIdempotencyKey(payKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                        .isPresent());

        // Deliver FAILED for attempt #0. The platform reopens the intent and sends
        // attempt #1 under a fresh channel key; its SUCCESS callback stays queued.
        channel.deliverPending();
        Await.until("attempt one to be sent", () -> {
            var request = requestRepository.findByIdempotencyKey(payKey).orElseThrow();
            return request.getStatus() == PaymentRequestStatus.SENT
                    && request.getChannelAttempt() == 1
                    && paymentEntries(orderNo).size() == 1;
        });

        // The gateway redelivers the old FAILED outcome for #0 before SUCCESS for
        // #1 arrives. It must be stale now, not evidence that #1 failed.
        channel.redeliverAll();
        Await.settle();

        var afterReplay = requestRepository.findByIdempotencyKey(payKey).orElseThrow();
        assertThat(afterReplay.getStatus()).isEqualTo(PaymentRequestStatus.SENT);
        assertThat(afterReplay.getChannelAttempt())
                .as("old #0 rejection must not mint attempt #2")
                .isEqualTo(1);
        assertThat(paymentEntries(orderNo))
                .as("a stale FAILED callback must not create a second successful charge")
                .hasSize(1);

        channel.deliverPending();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);
        assertThat(paymentEntries(orderNo)).hasSize(1);
    }

    private java.util.List<ChannelEntry> refundEntries(String orderNo) {
        return entries(orderNo, ChannelEntryType.REFUND);
    }

    private java.util.List<ChannelEntry> paymentEntries(String orderNo) {
        return entries(orderNo, ChannelEntryType.PAYMENT);
    }

    private java.util.List<ChannelEntry> entries(String orderNo, ChannelEntryType type) {
        return channel.ledger().stream()
                .filter(entry -> entry.orderNo().equals(orderNo))
                .filter(entry -> entry.type() == type)
                .toList();
    }
}
