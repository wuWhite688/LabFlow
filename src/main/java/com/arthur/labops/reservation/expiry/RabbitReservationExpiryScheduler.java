package com.arthur.labops.reservation.expiry;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Schedules reservation deadlines via <strong>per-message delay queues</strong> with
 * queue-level TTL + dead-letter to the shared expiry work queue.
 *
 * <p>This intentionally replaces the previous design (single FIFO delay queue +
 * per-message {@code expiration}) which suffered head-of-line blocking when a
 * long-TTL message sat at the queue head.
 */
@Component
@ConditionalOnProperty(name = "labops.reservation-expiry.mode", havingValue = "rabbit")
public class RabbitReservationExpiryScheduler implements ReservationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RabbitReservationExpiryScheduler.class);

    /** Extra idle time before RabbitMQ auto-deletes an empty per-delay queue. */
    static final long QUEUE_EXPIRES_GRACE_MS = 60_000L;

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitExpiryTopologyProperties topology;

    public RabbitReservationExpiryScheduler(AmqpAdmin amqpAdmin,
                                            RabbitTemplate rabbitTemplate,
                                            RabbitExpiryTopologyProperties topology) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.topology = topology;
    }

    @Override
    public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        long delayMillis = Math.max(1L, Duration.between(Instant.now(), deadline).toMillis());
        long deadlineEpochMs = deadline.toEpochMilli();
        String delayQueue = topology.delayQueueName(kind, reservationId, deadlineEpochMs);

        Map<String, Object> args = new HashMap<>();
        // Queue-level TTL: every message in this private queue has the same delay.
        args.put("x-message-ttl", delayMillis);
        args.put("x-dead-letter-exchange", topology.getExpiryExchange());
        args.put("x-dead-letter-routing-key", topology.getExpiryRoutingKey());
        // Auto-delete leftover empty delay queues after delay + grace.
        args.put("x-expires", delayMillis + QUEUE_EXPIRES_GRACE_MS);

        Queue queue = new Queue(delayQueue, true, false, false, args);
        amqpAdmin.declareQueue(queue);

        // Default exchange routes by queue name; message itself has no per-message TTL.
        rabbitTemplate.convertAndSend("", delayQueue, ReservationDeadlinePayload.encode(kind, reservationId));

        log.info(
                "RabbitMQ deadline scheduled kind={} reservationId={} delayMs={} deadline={} delayQueue={} dlx={} (per-queue TTL, no shared FIFO delay)",
                kind,
                reservationId,
                delayMillis,
                deadline,
                delayQueue,
                topology.getExpiryExchange());
    }

    /**
     * Deletes the private delay queue, which takes the pending message with it.
     *
     * <p>{@code x-expires} already bounds how long an <em>empty</em> queue lingers,
     * but it does not touch a queue that still holds an undelivered message: that
     * one survives its full TTL and then dead-letters work everyone already knows
     * is moot. Deleting on settle is what keeps the broker's queue count
     * proportional to open reservations rather than to all reservations ever made.
     */
    @Override
    public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        if (deadline == null) {
            return;
        }
        String delayQueue = topology.delayQueueName(kind, reservationId, deadline.toEpochMilli());
        try {
            boolean deleted = amqpAdmin.deleteQueue(delayQueue);
            log.info("RabbitMQ deadline cancelled kind={} reservationId={} delayQueue={} deleted={}",
                    kind, reservationId, delayQueue, deleted);
        } catch (AmqpException exception) {
            // Cleanup only. The state guard on the firing path already makes a
            // surviving message harmless, so a broker hiccup here must not fail
            // the business transaction that triggered it.
            log.warn("Failed to delete delay queue {} for reservation {}", delayQueue, reservationId, exception);
        }
    }
}
