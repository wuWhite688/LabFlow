package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.ReconciliationReport;
import com.arthur.labops.payment.reconcile.ReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Duplicate <em>initiation</em>, which is a different problem from a duplicate
 * callback and is not covered by callback idempotency.
 *
 * <p>Between asking the channel to charge and the callback coming back, the order
 * is still AWAITING_PAYMENT. A second request in that window creates a second
 * <strong>real</strong> channel transaction, with its own transaction id — so the
 * idempotency key on the inbound side never even gets a chance to match.
 *
 * <p>The nastiest part is that reconciliation cannot save us here: the channel
 * collected twice and the ledger recorded twice, so both sides net to the same
 * (wrong) figure and the books look perfect. The only defence is a stable
 * idempotency key on the <em>outbound</em> request.
 */
@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/duplicate-initiation"
})
@AutoConfigureMockMvc
class DuplicatePaymentInitiationIntegrationTest {

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
    private ReconciliationService reconciliationService;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void payingTwiceBeforeTheCallbackLandsChargesTheUserOnlyOnce() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-TWICE", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        // The user taps pay, sees nothing happen (the callback has not returned
        // yet), and taps again. The order is still AWAITING_PAYMENT both times.
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);
        scenario.payViaApi(orderNo, "student", "student123");

        assertThat(channel.ledger())
                .as("one order, one full payment — a second tap must not create a second real charge")
                .filteredOn(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == ChannelEntryType.PAYMENT)
                .hasSize(1);

        channel.deliverPending();

        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(1L);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getPaidCents())
                .as("the user must be charged the order amount, not twice it")
                .isEqualTo(EXPECTED_AMOUNT_CENTS);

        // Reconciliation is deliberately checked last: it reports "balanced" either
        // way, which is exactly why it cannot be the defence against this bug.
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        channel.writeDailyBill(settlementDate);
        ReconciliationReport report = reconciliationService.reconcile(settlementDate);
        assertThat(report.discrepancies())
                .noneMatch(discrepancy -> discrepancy.orderNo().equals(orderNo));
    }
}
