package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A fault report takes the equipment offline and cancels the reservations on it.
 * Once reservations can be paid, that path has to refund exactly like the user's
 * own cancel does — a reservation closed out from under someone must not also
 * keep their money.
 *
 * <p>Both routes go through {@code ReservationClosureService} for this reason:
 * two copies of the transition is how one of them ends up missing the refund.
 */
@SpringBootTest(properties = {
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/fault-refund"
})
@AutoConfigureMockMvc
class FaultReportRefundsPaidReservationIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 5_000L;

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

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void takingEquipmentOfflineRefundsThePaidReservationItCancels() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("FAULT-PAID", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);

        String orderNo = PaymentService.orderNoFor(reservationId);
        channel.charge(orderNo, HOURLY_PRICE_CENTS);
        channel.deliverPending();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);

        // A teacher reports a fault, which takes the equipment offline and closes
        // the reservations standing on it.
        mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "载物台卡死",
                                "description", "设备无法正常使用，需要立即停机检修",
                                "priority", "HIGH"))))
                .andExpect(status().isCreated());

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("a paid reservation cancelled by a fault report owes the user a refund")
                .isEqualTo(ReservationStatus.REFUNDING);

        Await.until("the refund to reach the channel", () -> channel.ledger().stream()
                .anyMatch(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == com.arthur.labops.payment.channel.ChannelEntryType.REFUND));
        assertThat(channel.deliverPending())
                .as("the refund was actually requested from the channel")
                .isEqualTo(1);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow())
                .satisfies(order -> {
                    assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REFUNDED);
                    assertThat(order.getRefundedCents()).isEqualTo(HOURLY_PRICE_CENTS);
                    assertThat(order.netCents()).isZero();
                });
    }
}
