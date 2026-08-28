package com.arthur.labops.reservation.expiry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatuses;

/**
 * DB 兜底扫描：即便本地定时或 RabbitMQ 延时消息失败，也能过期待审批预约，并同步设备状态。
 * 每条预约在独立事务里按 Equipment → Reservation 加锁，避免与审批/报修形成死锁。
 */
@Component
public class ReservationExpiryCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryCompensationJob.class);

    private final ReservationRepository reservationRepository;
    private final ReservationExpirationService expirationService;
    private final EquipmentStatusService equipmentStatusService;

    public ReservationExpiryCompensationJob(ReservationRepository reservationRepository,
                                            ReservationExpirationService expirationService,
                                            EquipmentStatusService equipmentStatusService) {
        this.reservationRepository = reservationRepository;
        this.expirationService = expirationService;
        this.equipmentStatusService = equipmentStatusService;
    }

    @Scheduled(fixedDelayString = "${labops.reservation-expiry.scan-interval:30000}")
    public void expireOverduePendingReservations() {
        Instant now = Instant.now();
        List<Long> overdueIds = new ArrayList<>(reservationRepository.findOverduePendingIds(now));
        for (Long reservationId : overdueIds) {
            try {
                expirationService.expireIfPending(reservationId);
            } catch (Exception exception) {
                log.warn("Failed to expire reservation {}", reservationId, exception);
            }
        }

        List<Long> equipmentIds = new ArrayList<>(reservationRepository.findEquipmentIdsWithReservationsInStatus(ReservationStatuses.CONFIRMED));
        for (Long equipmentId : equipmentIds) {
            try {
                equipmentStatusService.sync(equipmentId);
            } catch (Exception exception) {
                log.warn("Failed to sync equipment {}", equipmentId, exception);
            }
        }
    }
}
