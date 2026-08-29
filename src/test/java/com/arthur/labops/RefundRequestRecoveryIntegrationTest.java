package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.ReconciliationReport;
import com.arthur.labops.payment.reconcile.ReconciliationService;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The refund request itself fails on the way out.
 *
 * <p>The cancellation has already committed and the reservation is sitting in
 * REFUNDING. If the outbound call is fire-and-forget, that is where it stays
 * forever — and reconciliation cannot rescue it, which was the original
 * justification for leaving it fire-and-forget. The request never reached the
 * channel, so the channel has no refund and neither do we: both sides still show
 * the original payment and agree perfectly. On a later settlement date the order
 * does not even enter the comparison, because neither side has activity that day.
 *
 * <p>So the recovery has to be a durable, retried outbound request, and the retry
 * has to carry a stable idempotency key or it becomes a double-refund bug.
 */
@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.outbound.retry-interval=200ms",
        "labops.payment.channel.bill-directory=target/test-channel-bills/refund-recovery"
})
@AutoConfigureMockMvc
class RefundRequestRecoveryIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 5_000L;

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
    private ReconciliationService reconciliationService;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void aRefundRequestThatFailsOnTheWayOutIsRetriedUntilItLands() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REF-RETRY", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        scenario.payViaApi(orderNo, "student", "student123");
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);

        // The channel is unreachable exactly when we try to refund.
        channel.failNextOutbound(1);
        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");

        assertThat(channel.ledger())
                .as("the first attempt genuinely did not reach the channel")
                .filteredOn(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.REFUND)
                .isEmpty();

        // Nothing else happens: no user request, no callback. Recovery has to come
        // from the platform itself.
        awaitReservation(reservationId, ReservationStatus.CANCELLED);

        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
        assertThat(order.netCents()).isZero();

        assertThat(channel.ledger())
                .as("the retry must refund once, not once per attempt")
                .filteredOn(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.REFUND)
                .hasSize(1);

        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        channel.writeDailyBill(settlementDate);
        ReconciliationReport report = reconciliationService.reconcile(settlementDate);
        assertThat(report.discrepancies())
                .noneMatch(discrepancy -> discrepancy.orderNo().equals(orderNo));
    }

    private void awaitReservation(Long reservationId, ReservationStatus expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (reservationRepository.findById(reservationId).orElseThrow().getStatus() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("a stuck REFUNDING reservation is money the platform is holding with no path back")
                .isEqualTo(expected);
    }
}
