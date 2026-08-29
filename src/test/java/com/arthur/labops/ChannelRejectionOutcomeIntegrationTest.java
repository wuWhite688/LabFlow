package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentController;
import com.arthur.labops.payment.PaymentDispatchService;
import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentProperties;
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
 * What happens after the channel says no.
 *
 * <p>The previous round taught the callback path not to treat a FAILED report as
 * money. It stopped there, and the outbound half never heard about it: the
 * request had already been marked SENT when the channel accepted it, SENT counts
 * as settled, and settled requests are never offered again. So a refund the
 * channel then rejected left the reservation in REFUNDING permanently, with the
 * money still at the channel and both ledgers holding only the original payment
 * — balanced books over a user who was never paid back.
 *
 * <p>Retrying it needs a fresh channel-facing key. The idempotency key names the
 * intent and must stay stable, but the channel has already made a final decision
 * about the attempt presented under it; asking again with the same one is a
 * guaranteed no-op at any gateway that honours idempotency at all.
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

    @Autowired
    private PaymentDispatchService dispatchService;

    @Autowired
    private PaymentProperties paymentProperties;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    /**
     * A cancellation refund the channel accepts and then rejects. The user is owed
     * their money and no automated path is left to send it again.
     */
    @Test
    void aRefundTheChannelRejectsIsSentAgainUnderAFreshKey() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-REF", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, HOURLY_PRICE_CENTS);
        channel.deliverPending();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);

        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);
        scenario.cancelAsStudent(reservationId);
        Await.until("the refund to be accepted by the channel", () ->
                requestRepository.findByIdempotencyKey(refundKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                        .isPresent());
        assertThat(refundEntries(orderNo)).hasSize(1);

        // The channel changes its mind: the callback it queued never lands, and it
        // reports the transaction as failed instead.
        channel.discardPending();
        postCallback(orderNo, lastRefund(orderNo).channelTxnId(), "REFUND", HOURLY_PRICE_CENTS, "FAILED")
                .andExpect(status().isOk());

        assertThat(requestRepository.findByIdempotencyKey(refundKey).orElseThrow().getStatus())
                .as("a rejected attempt is not a delivered one; the refund is still owed")
                .isNotEqualTo(PaymentRequestStatus.SENT);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.REFUNDING);

        dispatchService.attempt(refundKey);
        Await.until("a second refund attempt to reach the channel", () -> refundEntries(orderNo).size() == 2);

        channel.deliverPending();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("the cancellation completes once the money is actually back")
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    /**
     * The mirror case. A payment the channel rejects leaves the order awaiting
     * payment, which is correct — but the intent is stuck SENT, so tapping pay
     * again produces nothing at all and the reservation quietly runs out its
     * window.
     */
    @Test
    void aPaymentTheChannelRejectsCanBeAttemptedAgain() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-PAY", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);
        Await.until("the first charge to reach the channel", () -> paymentEntries(orderNo).size() == 1);

        channel.discardPending();
        postCallback(orderNo, lastPayment(orderNo).channelTxnId(), "PAYMENT", HOURLY_PRICE_CENTS, "FAILED")
                .andExpect(status().isOk());

        assertThat(scenario.payViaApi(orderNo, "student", "student123"))
                .as("the order is still payable, so asking to pay it must do something")
                .isEqualTo(200);
        Await.until("a second charge to reach the channel", () -> paymentEntries(orderNo).size() == 2);

        channel.deliverPending();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
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

    private ChannelEntry lastRefund(String orderNo) {
        java.util.List<ChannelEntry> found = refundEntries(orderNo);
        return found.get(found.size() - 1);
    }

    private ChannelEntry lastPayment(String orderNo) {
        java.util.List<ChannelEntry> found = paymentEntries(orderNo);
        return found.get(found.size() - 1);
    }

    private org.springframework.test.web.servlet.ResultActions postCallback(
            String orderNo, String key, String type, long amountCents, String status) throws Exception {
        return mockMvc.perform(post("/api/payments/callback")
                .header(PaymentController.CALLBACK_TOKEN_HEADER, paymentProperties.getCallbackToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "orderNo", orderNo,
                        "idempotencyKey", key,
                        "type", type,
                        "amountCents", amountCents,
                        "channelTxnId", key,
                        "status", status,
                        "occurredAt", Instant.now().toString()))));
    }
}
