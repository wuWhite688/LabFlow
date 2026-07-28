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
        log.info("RabbitMQ expiry message consumed reservationId={} queue={}",
                reservationId, topology.getExpiryQueue());
        boolean expired = expirationService.expireIfPending(Long.valueOf(reservationId));
        log.info("RabbitMQ expiry processed reservationId={} expired={}", reservationId, expired);
    }
}
