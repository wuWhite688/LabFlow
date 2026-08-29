package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.reservation.expiry.LocalReservationExpiryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deadlines must not outlive the reservations they belong to.
 *
 * <p>Cancelling used to leave the approval timer armed until its original
 * instant. Firing it was harmless — the state guard rejects it — but the
 * scheduler's queue grew with every reservation ever made rather than with the
 * ones still open, and the payment window would have added a second timer per
 * reservation on top of that.
 *
 * <p>Measured as a delta around each call, not as an absolute count: the
 * scheduler is a singleton shared with every other test in this context.
 */
@SpringBootTest(properties = {
        "labops.payment.window=30m",
        "labops.reservation-approval-timeout=30m",
        "labops.payment.channel.callback-mode=MANUAL",
        "labops.payment.channel.bill-directory=target/test-channel-bills/deadline-leak"
})
@AutoConfigureMockMvc
class ReservationDeadlineLeakIntegrationTest {

    private static final long HOURLY_PRICE_CENTS = 4_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimulatedPaymentChannel channel;

    @Autowired
    private LocalReservationExpiryScheduler scheduler;

    private PaymentScenario scenario;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
        channel.reset();
        scenario = new PaymentScenario(mockMvc, objectMapper);
    }

    @Test
    void cancellingAPendingReservationDropsItsApprovalDeadline() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DL-PENDING", 0L);

        int before = scheduler.armedCount();
        Long reservationId = scenario.createReservation(equipmentId, 1);
        assertThat(scheduler.armedCount() - before)
                .as("creating a reservation arms its approval deadline")
                .isEqualTo(1);

        scenario.cancelAsStudent(reservationId);
        assertThat(scheduler.armedCount())
                .as("cancelling must take the approval deadline with it")
                .isEqualTo(before);
    }

    @Test
    void approvingSwapsTheApprovalDeadlineForThePaymentWindowRatherThanAddingToIt() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DL-APPROVE", HOURLY_PRICE_CENTS);

        int before = scheduler.armedCount();
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);

        assertThat(scheduler.armedCount() - before)
                .as("a reservation awaiting payment holds one deadline, not two")
                .isEqualTo(1);

        // Paying settles the payment window too.
        channel.charge(PaymentService.orderNoFor(reservationId), HOURLY_PRICE_CENTS);
        channel.deliverPending();

        assertThat(scheduler.armedCount())
                .as("a paid reservation has nothing left to wait for")
                .isEqualTo(before);
    }

    @Test
    void cancellingWhileAwaitingPaymentDropsThePaymentWindow() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DL-UNPAID", HOURLY_PRICE_CENTS);

        int before = scheduler.armedCount();
        Long reservationId = scenario.createReservation(equipmentId, 1);
        scenario.approve(reservationId);
        assertThat(scheduler.armedCount() - before).isEqualTo(1);

        assertThat(scenario.cancelAsStudent(reservationId).get("status")).isEqualTo("CANCELLED");
        assertThat(scheduler.armedCount())
                .as("an abandoned checkout must not leave its window armed")
                .isEqualTo(before);
    }

    /**
     * Approving a free reservation opens no order and therefore no payment window,
     * but it still has to release the approval deadline it just consumed.
     */
    @Test
    void approvingAFreeReservationLeavesNoDeadlineBehind() throws Exception {
        Long equipmentId = scenario.createPricedEquipment("DL-FREE", 0L);

        int before = scheduler.armedCount();
        Long reservationId = scenario.createReservation(equipmentId, 1);
        assertThat(scenario.approve(reservationId).get("status")).isEqualTo("APPROVED");

        assertThat(scheduler.armedCount()).isEqualTo(before);
    }
}
