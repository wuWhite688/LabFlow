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
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A terminal rejection can callback synchronously, before attempt() has returned
 * to mark that outbound call SENT. The callback must still advance the intent to
 * a fresh channel key, and completion of the old attempt must not overwrite that
 * newer state afterwards.
 */
@SpringBootTest(properties = {
        "labops.payment.window=10m",
        "labops.payment.channel.callback-mode=IMMEDIATE",
        "labops.payment.channel.bill-directory=target/test-channel-bills/channel-rejection-immediate"
})
@AutoConfigureMockMvc
class ImmediateChannelRejectionIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 6_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimulatedPaymentChannel channel;
    @Autowired private PaymentRequestRepository requestRepository;
    @Autowired private PaymentOrderRepository orderRepository;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void anImmediateFinalRejectionStillAdvancesToAFreshAttempt() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("REJ-IMM", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);
        String payKey = PaymentIdempotency.payment(orderNo);

        channel.rejectNextOutboundFinal(1);
        assertThat(scenario.payViaApi(orderNo, "student", "student123")).isEqualTo(200);

        Await.until("the outbound request to settle after the immediate rejection", () ->
                requestRepository.findByIdempotencyKey(payKey)
                        .filter(request -> request.getStatus() == PaymentRequestStatus.SENT)
                        .isPresent());
        Await.settle();

        assertThat(requestRepository.findByIdempotencyKey(payKey).orElseThrow().getChannelAttempt())
                .as("the rejected #0 attempt must advance to #1")
                .isEqualTo(1);
        assertThat(channel.ledger())
                .filteredOn(entry -> entry.orderNo().equals(orderNo))
                .filteredOn(entry -> entry.type() == ChannelEntryType.PAYMENT)
                .as("only the fresh successful attempt is money")
                .hasSize(1);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.PAID);
    }
}
