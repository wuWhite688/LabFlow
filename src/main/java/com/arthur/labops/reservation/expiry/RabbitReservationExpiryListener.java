package com.arthur.labops.reservation.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class RabbitReservationExpiryListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitReservationExpiryListener.class);

    private final ReservationExpirationService expirationService;
    private final RabbitExpiryTopologyProperties topology;

    public RabbitReservationExpiryListener(ReservationExpirationService expirationService,
                                           RabbitExpiryTopologyProperties topology) {
        this.expirationService = expirationService;
        this.topology = topology;
    }

    @RabbitListener(queues = "#{@reservationExpiryQueue.name}")
    public void expire(String reservationId) {
        Long id = parseReservationId(reservationId);
        if (id == null) {
            log.warn("Ignoring non-numeric RabbitMQ expiry payload queue={}", topology.getExpiryQueue());
            return;
        }
        log.info("RabbitMQ expiry message consumed reservationId={} queue={}",
                id, topology.getExpiryQueue());
        boolean expired = expirationService.expireIfPending(id);
        log.info("RabbitMQ expiry processed reservationId={} expired={}", id, expired);
    }

    private static Long parseReservationId(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(reservationId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
