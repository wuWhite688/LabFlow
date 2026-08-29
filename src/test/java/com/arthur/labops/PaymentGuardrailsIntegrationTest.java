package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.payment.PaymentController;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentProperties;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The guardrails around the money path — each one covering a way the earlier
 * version could be wrong quietly rather than loudly.
 */
@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/guardrails"
})
@AutoConfigureMockMvc
class PaymentGuardrailsIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimulatedPaymentChannel channel;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentOrderRepository orderRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentProperties paymentProperties;

    @Autowired
    private PaymentRequestRepository requestRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    /**
     * Billing charges per <em>started</em> minute. Truncating instead — which is
     * what {@code Duration.toMinutes()} does — prices a 59-second reservation at
     * zero, and a zero price is indistinguishable from free equipment, so the
     * reservation silently skips the payment flow entirely. Reservations have no
     * minimum length, so this was reachable from the public API.
     */
    @Test
    void aSubMinuteReservationOnPricedEquipmentIsStillCharged() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("BILL-SUB", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, start.plusSeconds(59));

        assertThat(scenario.approve(reservationId).get("status"))
                .as("a priced reservation must never fall through to the free path")
                .isEqualTo("AWAITING_PAYMENT");

        assertThat(orderRepository.findByOrderNo(PaymentService.orderNoFor(reservationId)).orElseThrow())
                .satisfies(order -> assertThat(order.getAmountCents())
                        .as("59 seconds is one started minute")
                        .isEqualTo(HOURLY_PRICE_CENTS / 60));
    }

    /** 61 seconds is two started minutes, not one. */
    @Test
    void billingRoundsPartMinutesUp() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("BILL-CEIL", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, start.plusSeconds(61));
        scenario.approve(reservationId);

        assertThat(orderRepository.findByOrderNo(PaymentService.orderNoFor(reservationId)).orElseThrow()
                .getAmountCents())
                .isEqualTo(2 * (HOURLY_PRICE_CENTS / 60));
    }

    /**
     * The window holds the slot, so it must not outlive the slot. A reservation
     * starting in two minutes cannot be given ten minutes to pay for it.
     */
    @Test
    void thePaymentWindowNeverOutlivesTheReservationStart() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("WIN-CLAMP", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, start.plus(1, ChronoUnit.HOURS));

        scenario.approve(reservationId);

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(reservation.getPaymentDeadline())
                .as("clamped to the start time rather than now + %s", paymentProperties.getWindow())
                .isEqualTo(reservation.getStartTime());
    }

    /**
     * Viewing and paying are different rights. A teacher has a reason to look at a
     * student's order; moving that student's money is not one of them.
     */
    @Test
    void aTeacherMayViewAStudentOrderButNotPayIt() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PERM-SPLIT", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        mockMvc.perform(get("/api/payments/orders/" + orderNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123")))
                .andExpect(status().isOk());

        assertThat(scenario.payViaApi(orderNo, "teacher", "teacher123"))
                .as("only the payer may trigger a charge on their own order")
                .isEqualTo(403);

        Await.settle();
        assertThat(channel.ledger())
                .as("a rejected pay request must not have reached the channel")
                .noneMatch(entry -> entry.orderNo().equals(orderNo));
    }

    /**
     * Field lengths are validated at the edge so an over-long value cannot reach
     * the database, come back as a constraint violation, and be mistaken for a
     * duplicate-key collision — which would report a real payment as already
     * recorded and drop it.
     */
    @Test
    void anOversizedCallbackFieldIsRejectedRatherThanMistakenForADuplicate() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("CB-SIZE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        String oversizedKey = "K".repeat(200);
        mockMvc.perform(post("/api/payments/callback")
                        .header(PaymentController.CALLBACK_TOKEN_HEADER, paymentProperties.getCallbackToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderNo", orderNo,
                                "idempotencyKey", oversizedKey,
                                "type", "PAYMENT",
                                "amountCents", 6000,
                                "channelTxnId", "CH-X-1",
                                "status", "SUCCESS",
                                "occurredAt", Instant.now().toString()))))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.countByOrderNo(orderNo)).isZero();
    }

    @Test
    void aCallbackWithoutTheChannelTokenIsRejected() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("CB-TOKEN", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        mockMvc.perform(post("/api/payments/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderNo", orderNo,
                                "idempotencyKey", "CH-NOPE-1",
                                "type", "PAYMENT",
                                "amountCents", 6000,
                                "channelTxnId", "CH-NOPE-1",
                                "status", "SUCCESS",
                                "occurredAt", Instant.now().toString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PAYMENT_CALLBACK_UNAUTHORIZED"));

        assertThat(transactionRepository.countByOrderNo(orderNo)).isZero();
    }

    /**
     * {@code Duration.toSeconds()} truncates too. A 500ms reservation is legal —
     * there is no minimum length — and truncating to whole seconds prices it at
     * zero, which is once again indistinguishable from free equipment. Rounding
     * has to happen below the second, not at it.
     */
    @Test
    void aSubSecondReservationOnPricedEquipmentIsStillCharged() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("BILL-MS", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, start.plusMillis(500));

        assertThat(scenario.approve(reservationId).get("status"))
                .as("half a second is still a started minute, not free")
                .isEqualTo("AWAITING_PAYMENT");
        assertThat(orderRepository.findByOrderNo(PaymentService.orderNoFor(reservationId)).orElseThrow()
                .getAmountCents())
                .isEqualTo(HOURLY_PRICE_CENTS / 60);
    }

    /** 60.5 seconds is two started minutes; rounding at the second boundary would say one. */
    @Test
    void billingRoundsSubSecondRemaindersUpToo() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("BILL-FRAC", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Long reservationId = createReservation(equipmentId, start, start.plusMillis(60_500));
        scenario.approve(reservationId);

        assertThat(orderRepository.findByOrderNo(PaymentService.orderNoFor(reservationId)).orElseThrow()
                .getAmountCents())
                .isEqualTo(2 * (HOURLY_PRICE_CENTS / 60));
    }

    /**
     * The channel reports outcomes, not just events. A callback that says the
     * payment FAILED must not be folded onto the order as money received — the
     * field is already carried through the request, so ignoring it means the
     * ledger records a payment that never happened.
     */
    @Test
    void aFailedPaymentCallbackIsNotTreatedAsMoneyReceived() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("CB-FAILED", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        mockMvc.perform(post("/api/payments/callback")
                        .header(PaymentController.CALLBACK_TOKEN_HEADER, paymentProperties.getCallbackToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderNo", orderNo,
                                "idempotencyKey", "CH-FAIL-1",
                                "type", "PAYMENT",
                                "amountCents", HOURLY_PRICE_CENTS,
                                "channelTxnId", "CH-FAIL-1",
                                "status", "FAILED",
                                "occurredAt", Instant.now().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        assertThat(transactionRepository.countByOrderNo(orderNo))
                .as("a failed attempt is not money and does not belong in the money ledger")
                .isZero();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow())
                .satisfies(order -> {
                    assertThat(order.getPaidCents()).isZero();
                    assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.AWAITING_PAYMENT);
                });
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.AWAITING_PAYMENT);
    }

    /**
     * Two genuinely simultaneous taps, racing the pre-check rather than arriving
     * one after the other. Neither caller did anything wrong, so neither should
     * get an error: both are asking for the same intent, and it already exists.
     */
    @Test
    void twoSimultaneousPayRequestsBothSucceedAndOweOnePayment() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-RACE2", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) {
                results.add(pool.submit(() -> {
                    barrier.await(20, TimeUnit.SECONDS);
                    return scenario.payViaApi(orderNo, "student", "student123");
                }));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS))
                        .as("a lost enqueue race is not the caller's problem")
                        .isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(requestRepository.findByOrderNoOrderByIdAsc(orderNo))
                .as("one intent, however many callers asked for it")
                .hasSize(1);
        Await.until("the charge to reach the channel", () -> channel.ledger().stream()
                .anyMatch(entry -> entry.orderNo().equals(orderNo)));
        Await.settle();
        assertThat(channel.ledger())
                .filteredOn(entry -> entry.orderNo().equals(orderNo))
                .hasSize(1);
    }

    private Long createReservation(Long equipmentId, Instant start, Instant end) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "计费边界测试 " + UUID.randomUUID(),
                                "startTime", start.toString(),
                                "endTime", end.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body =
                objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return Long.valueOf(String.valueOf(body.get("id")));
    }
}
