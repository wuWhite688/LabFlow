package com.arthur.labops.reservation.expiry;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.reservation.ReservationRepository;

/**
 * DB 兜底扫描：即便本地定时或 RabbitMQ 延时消息失败，也能过期待审批预约，并同步设备状态。
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
    @Transactional
    public void expireOverduePendingReservations() {
        Instant now = Instant.now();
        Set<Long> equipmentIds = new HashSet<>();

        List<Long> overdueIds = reservationRepository.findOverduePendingIds(now);
        for (Long reservationId : overdueIds) {
            try {
                boolean expired = expirationService.expireIfPending(reservationId);
                if (expired) {
                    reservationRepository.findById(reservationId).ifPresent(reservation ->
                            equipmentIds.add(reservation.getEquipment().getId()));
                }
            } catch (Exception exception) {
                log.warn("Failed to expire reservation {}", reservationId, exception);
            }
        }

        equipmentIds.addAll(reservationRepository.findEquipmentIdsWithApprovedReservations());
        for (Long equipmentId : equipmentIds) {
            equipmentStatusService.sync(equipmentId);
        }
    }
}
