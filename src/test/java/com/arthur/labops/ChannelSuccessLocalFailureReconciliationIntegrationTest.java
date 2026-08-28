package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.payment.reconcile.DiscrepancyType;
import com.arthur.labops.payment.reconcile.ReconciliationDiscrepancy;
import com.arthur.labops.payment.reconcile.ReconciliationReport;
import com.arthur.labops.payment.reconcile.ReconciliationService;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderCategory;
import com.arthur.labops.workorder.WorkOrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Scenario 3 — the channel took the money, the local side never recorded it.
 *
 * <p>The callback is queued and then dropped, which is what a failed local write
 * or a delivery that never lands looks like from the outside: the channel's books
 * have the payment, ours do not. Nothing in the request path can notice this —
 * there is no request. Only the T+1 comparison finds it, and it has to find it by
 * looking at what the ledger actually recorded rather than at what the order was
 * expected to collect, because those two agree here.
 */
@SpringBootTest(properties = "labops.payment.channel.callback-mode=MANUAL")
@AutoConfigureMockMvc
class ChannelSuccessLocalFailureReconciliationIntegrationTest {

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
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private FaultWorkOrderRepository workOrderRepository;

    @Autowired
    private EquipmentRepository equipmentRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void channelSettledPaymentMissingFromLocalLedgerRaisesDiscrepancyTicket() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-LOST", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 2);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, CHARGED_CENTS);
        assertThat(channel.discardPending())
                .as("the callback is queued and then lost, exactly once")
                .isEqualTo(1);

        assertThat(transactionRepository.countByOrderNo(orderNo))
                .as("nothing reached the local ledger")
                .isZero();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.AWAITING_PAYMENT);
        assertThat(channel.ledger())
                .as("the channel still has the payment on its books")
                .anyMatch(entry -> entry.orderNo().equals(orderNo) && entry.amountCents() == CHARGED_CENTS);

        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        channel.writeDailyBill(settlementDate);
        ReconciliationReport report = reconciliationService.reconcile(settlementDate);

        List<ReconciliationDiscrepancy> forOrder = report.discrepancies().stream()
                .filter(discrepancy -> discrepancy.orderNo().equals(orderNo))
                .toList();
        assertThat(forOrder).hasSize(1);
        assertThat(forOrder.get(0).type()).isEqualTo(DiscrepancyType.MISSING_LOCALLY);
        assertThat(forOrder.get(0).channelNetCents()).isEqualTo(CHARGED_CENTS);
        assertThat(forOrder.get(0).localNetCents()).isZero();

        List<FaultWorkOrder> tickets = workOrderRepository.findAll().stream()
                .filter(workOrder -> workOrder.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY)
                .filter(workOrder -> workOrder.getEquipment().getId().equals(equipmentId))
                .toList();
        assertThat(tickets).hasSize(1);
        FaultWorkOrder ticket = tickets.get(0);
        assertThat(ticket.getStatus()).isEqualTo(WorkOrderStatus.SUBMITTED);
        assertThat(ticket.getDiscrepancyKey()).isEqualTo(forOrder.get(0).key());
        assertThat(ticket.getDescription()).contains(String.valueOf(CHARGED_CENTS));

        // An accounting break says nothing about the hardware.
        assertThat(ticket.isEquipmentTakenOffline()).isFalse();
        assertThat(equipmentRepository.findById(equipmentId).orElseThrow().getStatus())
                .isNotEqualTo(EquipmentStatus.MAINTENANCE);

        // Re-running the same day is expected operational behaviour and must not
        // pile up a second ticket for the same finding.
        reconciliationService.reconcile(settlementDate);
        assertThat(workOrderRepository.findAll().stream()
                .filter(workOrder -> workOrder.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY)
                .filter(workOrder -> workOrder.getEquipment().getId().equals(equipmentId))
                .count())
                .isEqualTo(1L);

        // A payment discrepancy must not block someone reporting a real fault.
        assertThat(workOrderRepository.existsByEquipmentIdAndCategoryAndStatusIn(
                equipmentId, WorkOrderCategory.FAULT, EnumSet.allOf(WorkOrderStatus.class)))
                .isFalse();
    }
}
