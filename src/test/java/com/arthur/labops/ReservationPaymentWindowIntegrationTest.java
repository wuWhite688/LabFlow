package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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

import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The payment window, end to end.
 *
 * <p>An approved-but-unpaid reservation holds the calendar slot — otherwise
 * someone else books it out from under a user who is mid-payment. That is also
 * why the window is short and why timing out has to give the slot back: an
 * abandoned checkout must not sit on the equipment until the reservation's own
 * end time.
 */
@SpringBootTest(properties = {
        "labops.payment.window=500ms",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/payment-window"
})
@AutoConfigureMockMvc
class ReservationPaymentWindowIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 3_000L;

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
    void awaitingPaymentHoldsTheSlotAndTimingOutGivesItBack() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-WIN", HOURLY_PRICE_CENTS);
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(2, ChronoUnit.HOURS);

        Long first = createReservation(equipmentId, start, end);
        Long second = createReservation(equipmentId, start, end);

        // Overlapping PENDING requests coexist; approval is what commits the slot.
        mockMvc.perform(decision(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));

        mockMvc.perform(decision(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));

        String orderNo = PaymentService.orderNoFor(first);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.AWAITING_PAYMENT);
        assertThat(reservationRepository.findById(first).orElseThrow().getPaymentDeadline()).isNotNull();

        awaitStatus(first, ReservationStatus.EXPIRED);

        Reservation closed = reservationRepository.findById(first).orElseThrow();
        assertThat(closed.getPaymentDeadline())
                .as("a closed reservation must not keep a deadline pointing at nothing")
                .isNull();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .as("timing out closes the order, it does not leave it collectable")
                .isEqualTo(PaymentOrderStatus.CLOSED);

        // The slot is free again, so the runner-up can now be approved.
        mockMvc.perform(decision(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    void payingBeforeTheWindowClosesConfirmsTheReservation() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-INTIME", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, HOURLY_PRICE_CENTS);
        assertThat(channel.deliverPending()).isEqualTo(1);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);

        // The window has now elapsed, but the deadline finds a PAID reservation and
        // must leave it alone — the state guard, not the timer, is what decides.
        TimeUnit.MILLISECONDS.sleep(900);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PAID);
    }

    @Test
    void cancellingAfterPaymentRefundsAndOnlyThenClosesTheReservation() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("PAY-REFUND", HOURLY_PRICE_CENTS);
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        String orderNo = PaymentService.orderNoFor(reservationId);

        channel.charge(orderNo, HOURLY_PRICE_CENTS);
        channel.deliverPending();

        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("REFUNDING");
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("cancelling paid money does not close the reservation until the refund lands")
                .isEqualTo(ReservationStatus.REFUNDING);

        // The refund request leaves on a pool thread after commit, so wait for it to
        // reach the channel before draining the callback it queues.
        Await.until("the refund to reach the channel", () -> channel.ledger().stream()
                .anyMatch(entry -> entry.orderNo().equals(orderNo)
                        && entry.type() == com.arthur.labops.payment.channel.ChannelEntryType.REFUND));
        assertThat(channel.deliverPending()).isEqualTo(1);

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderStatus.REFUNDED);
    }

    private void awaitStatus(Long reservationId, ReservationStatus expected) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (reservationRepository.findById(reservationId).orElseThrow().getStatus() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(expected);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decision(Long id)
            throws Exception {
        return patch("/api/reservations/" + id + "/decision")
                .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "teacher", "teacher123"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("decision", "APPROVED")));
    }

    private Long createReservation(Long equipmentId, Instant start, Instant end) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "purpose", "支付窗口测试 " + UUID.randomUUID(),
                                "startTime", start.toString(),
                                "endTime", end.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        Map<String, Object> body =
                objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return Long.valueOf(String.valueOf(body.get("id")));
    }
}
