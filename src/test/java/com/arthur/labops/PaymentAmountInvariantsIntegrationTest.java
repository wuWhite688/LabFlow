package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The channel saying SUCCESS is not the same as the channel being right.
 *
 * <p>Every earlier round protected the <em>delivery</em> of money movements —
 * that a callback lands once, that an outbound request survives a failure, that
 * an intent stops applying when the reservation does. None of them ever asked
 * whether the amount on a movement made sense against the order it settles. A
 * ledger that folds on whatever number arrives is a ledger that can be told a
 * 6000-cent booking was paid for a single cent.
 */
@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/amount-invariants"
})
@AutoConfigureMockMvc
class PaymentAmountInvariantsIntegrationTest {

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
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentRequestRepository requestRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private FaultWorkOrderRepository workOrderRepository;

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
     * A payment for less than the order is owed must not settle it. Folding the
     * amount on unconditionally and stamping the order PAID means one cent buys a
     * booking — the callback carries the amount, so not comparing it is the
     * ledger choosing to trust a number it was handed.
     */
    @Test
    void aPaymentForLessThanTheAmountDueDoesNotSettleTheOrder() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("AMT-UNDER", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        postCallback(orderNo, "CH-UNDER-1", "PAYMENT", 1L, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        assertThat(transactionRepository.countByOrderNo(orderNo))
                .as("an amount that settles nothing does not belong on the order")
                .isZero();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow())
                .satisfies(order -> {
                    assertThat(order.getPaidCents()).isZero();
                    assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.AWAITING_PAYMENT);
                });
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("one cent must not buy a 6000-cent booking")
                .isEqualTo(ReservationStatus.AWAITING_PAYMENT);
    }

    /** Control: the amount that is actually owed settles the order as before. */
    @Test
    void aPaymentForExactlyTheAmountDueSettlesTheOrder() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("AMT-EXACT", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        postCallback(orderNo, "CH-EXACT-1", "PAYMENT", HOURLY_PRICE_CENTS, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
    }

    /**
     * Refunding more than was ever paid drives {@code refundedCents} past
     * {@code paidCents}, which makes the order's net negative — the platform's own
     * books claiming the channel owes it money for a booking the user paid for.
     */
    @Test
    void aRefundLargerThanWhatWasPaidIsRejected() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("AMT-OVER", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        channel.charge(orderNo, HOURLY_PRICE_CENTS);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);

        postCallback(orderNo, "CH-OVER-1", "REFUND", 2 * HOURLY_PRICE_CENTS, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow())
                .satisfies(order -> {
                    assertThat(order.getRefundedCents())
                            .as("a refund can never exceed what the channel is holding")
                            .isZero();
                    assertThat(order.netCents()).isNotNegative();
                    assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);
                });
    }

    /**
     * The stale-amount retry, which is the version of this that survives every
     * guard added so far.
     *
     * <p>A cancellation owes a full refund, the first attempt fails, and before
     * the retry runs an operator refunds part of it at the channel's end. The
     * retry's guard only asks whether anything is still refundable — and
     * something is — so it sends the amount frozen into the request when it was
     * created. Both sides then record more refunded than was ever paid, and
     * because both sides agree, reconciliation reports the books as balanced.
     */
    @Test
    void aRefundRetryDoesNotSendAnAmountThatIsNoLongerOwed() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("AMT-STALE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        channel.charge(orderNo, HOURLY_PRICE_CENTS);

        String refundKey = PaymentIdempotency.cancellationRefund(orderNo);
        channel.failNextOutbound(1);
        scenario.cancelAsStudent(reservationId);
        Await.until("the cancellation refund to fail its first attempt", () ->
                requestRepository.findByIdempotencyKey(refundKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.FAILED)
                        .isPresent());

        // An operator refunds part of it directly at the channel while our request
        // is still stuck. The callback lands, so the local ledger knows.
        channel.refund(orderNo, 2_000L);
        Await.until("the out-of-band partial refund to be recorded", () ->
                orderRepository.findByOrderNo(orderNo).orElseThrow().getRefundedCents() == 2_000L);

        dispatchService.attempt(refundKey);
        Await.settle();

        long refundedToChannel = channel.ledger().stream()
                .filter(entry -> entry.orderNo().equals(orderNo))
                .filter(entry -> entry.type() == ChannelEntryType.REFUND)
                .mapToLong(entry -> entry.amountCents())
                .sum();
        assertThat(refundedToChannel)
                .as("the platform must never refund more than it took")
                .isLessThanOrEqualTo(HOURLY_PRICE_CENTS);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().netCents())
                .as("net held by the channel can never go negative")
                .isNotNegative();
        assertThat(requestRepository.findByIdempotencyKey(refundKey).orElseThrow().getStatus())
                .as("an intent whose amount no longer matches reality must not stay sendable")
                .isEqualTo(PaymentRequestStatus.OBSOLETE);
        assertThat(workOrderRepository.existsByDiscrepancyKey("outbound|" + refundKey))
                .as("the platform cannot decide on its own whether the operator's refund replaced ours")
                .isTrue();
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
