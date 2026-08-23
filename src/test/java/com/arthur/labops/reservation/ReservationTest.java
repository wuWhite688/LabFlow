package com.arthur.labops.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.arthur.labops.equipment.Equipment;

class ReservationTest {

    @Test
    void approvalDeadlineNeverOutlivesRequestedWindow() {
        Instant start = Instant.now().plus(1, ChronoUnit.MINUTES);
        Instant end = start.plus(1, ChronoUnit.MINUTES);
        Reservation reservation = new Reservation(
                new Equipment("DEADLINE-001", "审批截止测试", "测试仪器", "D104"),
                1L,
                "Student",
                "验证审批截止时间",
                start,
                end,
                end.plus(15, ChronoUnit.MINUTES));

        assertThat(reservation.getExpiresAt()).isEqualTo(end);
    }
}
