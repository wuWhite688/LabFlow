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

import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.ReconciliationReport;
import com.arthur.labops.payment.reconcile.ReconciliationService;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderCategory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Scenario 2 — partial refund.
 *
 * <p>Half the money comes back. Nothing is wrong, and reconciliation must say so:
 * both sides net to the same figure. The trap is comparing what the order was
 * supposed to collect against what the channel is holding — those legitimately
 * differ the moment any refund exists, and a reconciliation that flags it will
 * bury the real breaks under noise.
 */
@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/partial-refund"
})
@AutoConfigureMockMvc
class PartialRefundReconciliationIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;
    private static final long PAID_CENTS = 12_000L;
    private static final long REFUNDED_CENTS = 6_000L;

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

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private FaultWorkOrderRepository workOrderRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void halfRefundedOrderStillReconcilesAndRaisesNoTicket() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-HALF", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, PAID_CENTS);
        // The session was cut short, so the lab refunds half through the channel.
        channel.refund(orderNo, REFUNDED_CENTS);

        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getPaidCents()).isEqualTo(PAID_CENTS);
        assertThat(order.getRefundedCents()).isEqualTo(REFUNDED_CENTS);
        assertThat(order.netCents()).isEqualTo(PAID_CENTS - REFUNDED_CENTS);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PARTIALLY_REFUNDED);
        assertThat(transactionRepository.countByOrderNo(orderNo)).isEqualTo(2L);

        // A partial refund is not a cancellation: the reservation is still on.
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);

        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        channel.writeDailyBill(settlementDate);
        ReconciliationReport report = reconciliationService.reconcile(settlementDate);

        assertThat(report.discrepancies())
                .as("payment minus refund matches on both sides, so nothing is out of balance")
                .noneMatch(discrepancy -> discrepancy.orderNo().equals(orderNo));
        // Scoped to this order on purpose: every test class shares one in-memory
        // database, so a global ticket count would be asserting on other tests'
        // leftovers rather than on this scenario.
        assertThat(workOrderRepository.findAll())
                .as("a balanced partial refund must not raise a discrepancy ticket")
                .filteredOn(workOrder -> workOrder.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY)
                .noneMatch(workOrder -> workOrder.getDiscrepancyKey() != null
                        && workOrder.getDiscrepancyKey().contains(orderNo));
    }
}
