package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransaction;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Scenario 1 — duplicate callback.
 *
 * <p>A gateway that does not see a timely acknowledgement redelivers. Recording
 * the replay a second time would double the money the platform believes it
 * collected, so the ingest has to swallow it silently: no second row, no error
 * back to the channel.
 */
@SpringBootTest(properties = "labops.payment.channel.callback-mode=IMMEDIATE")
@AutoConfigureMockMvc
class PaymentCallbackIdempotencyIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;
    private static final long EXPECTED_AMOUNT_CENTS = 12_000L;

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
    private ReservationRepository reservationRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void replayedCallbackIsSwallowedAndLeavesExactlyOneLedgerRow() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-DUP", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        assertThat(scenario.approve(reservationId).get("status")).isEqualTo("AWAITING_PAYMENT");

        String orderNo = PaymentService.orderNoFor(reservationId);
        assertThat(orderRepository.findByOrderNo(orderNo))
                .get()
                .extracting(PaymentOrder::getAmountCents, PaymentOrder::getStatus)
                .containsExactly(EXPECTED_AMOUNT_CENTS, PaymentOrderStatus.AWAITING_PAYMENT);

        channel.charge(orderNo, EXPECTED_AMOUNT_CENTS);

        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(1L);

        // The channel never heard back, so it sends the very same transaction again.
        assertThat(channel.redeliverAll()).isEqualTo(1);

        List<PaymentTransaction> ledger = transactionRepository.findByOrderNoOrderByIdAsc(orderNo);
        assertThat(ledger)
                .as("a redelivered callback must not add a second ledger row")
                .hasSize(1);
        assertThat(ledger.get(0).getAmountCents()).isEqualTo(EXPECTED_AMOUNT_CENTS);

        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getPaidCents())
                .as("the replay must not double what we believe we collected")
                .isEqualTo(EXPECTED_AMOUNT_CENTS);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PAID);

        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAID);
        assertThat(reservation.getPaymentDeadline()).isNull();
    }

    /**
     * The same duplicate, delivered concurrently. This is the case a
     * "have I seen this key?" read-then-write check cannot cover: both threads
     * read "no" before either writes. Only the unique index rejects the loser.
     */
    @Test
    void concurrentDuplicateDeliveriesStillLeaveOneLedgerRow() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-RACE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, EXPECTED_AMOUNT_CENTS);
        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(1L);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    channel.redeliverAll();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(transactionRepository.countByOrderNo(orderNo))
                .as("concurrent replays of one channel transaction must collapse to one row")
                .isEqualTo(1L);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getPaidCents())
                .isEqualTo(EXPECTED_AMOUNT_CENTS);
    }
}
