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

    private final ReservationDeadlineHandler deadlineHandler;
    private final RabbitExpiryTopologyProperties topology;

    public RabbitReservationExpiryListener(ReservationDeadlineHandler deadlineHandler,
                                           RabbitExpiryTopologyProperties topology) {
        this.deadlineHandler = deadlineHandler;
        this.topology = topology;
    }

    @RabbitListener(queues = "#{@reservationExpiryQueue.name}")
    public void expire(String payload) {
        ReservationDeadlinePayload.Decoded decoded = ReservationDeadlinePayload.decode(payload);
        if (decoded == null) {
            log.warn("Ignoring unparseable RabbitMQ deadline payload queue={}", topology.getExpiryQueue());
            return;
        }
        log.info("RabbitMQ deadline message consumed kind={} reservationId={} queue={}",
                decoded.kind(), decoded.reservationId(), topology.getExpiryQueue());
        deadlineHandler.fire(decoded.kind(), decoded.reservationId());
    }
}
