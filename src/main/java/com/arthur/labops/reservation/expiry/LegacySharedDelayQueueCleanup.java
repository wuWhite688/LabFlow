package com.arthur.labops.reservation.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Removes the legacy shared FIFO delay queue used by the old per-message TTL design.
 * New schedules never publish there; any stuck head-of-line messages would otherwise
 * remain until their original long TTL elapsed. Pending rows are still covered by
 * {@link ReservationExpiryCompensationJob}.
 */
@Component
@Order(50)
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class LegacySharedDelayQueueCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySharedDelayQueueCleanup.class);

    private final AmqpAdmin amqpAdmin;

    public LegacySharedDelayQueueCleanup(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean deleted = Boolean.TRUE.equals(
                    amqpAdmin.deleteQueue(RabbitExpiryTopologyProperties.LEGACY_SHARED_DELAY_QUEUE));
            if (deleted) {
                log.warn(
                        "Deleted legacy shared delay queue {} (replaced by per-reservation delay queues to avoid HOL blocking)",
                        RabbitExpiryTopologyProperties.LEGACY_SHARED_DELAY_QUEUE);
            }
        } catch (Exception exception) {
            log.info(
                    "Legacy delay queue {} not deleted (may already be absent): {}",
                    RabbitExpiryTopologyProperties.LEGACY_SHARED_DELAY_QUEUE,
                    exception.getMessage());
        }
        try {
            amqpAdmin.deleteExchange(RabbitExpiryTopologyProperties.LEGACY_DELAY_EXCHANGE);
        } catch (Exception ignored) {
            // exchange may already be gone — not critical for new path
        }
    }
}
