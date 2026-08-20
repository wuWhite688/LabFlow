package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Proves short and long delays are isolated into separate queues with queue-level TTL
 * (the property that eliminates shared-FIFO head-of-line blocking).
 */
class RabbitReservationExpirySchedulerTest {

    private RecordingAmqpAdmin amqpAdmin;
    private CapturingRabbitTemplate rabbitTemplate;
    private RabbitReservationExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        amqpAdmin = new RecordingAmqpAdmin();
        rabbitTemplate = new CapturingRabbitTemplate();
        scheduler = new RabbitReservationExpiryScheduler(
                amqpAdmin, rabbitTemplate, new RabbitExpiryTopologyProperties());
    }

    @Test
    void longThenShortDelayUseSeparateQueuesAndShortHasSmallerQueueTtl() {
        Instant now = Instant.now();
        Instant longExpiry = now.plusSeconds(900); // 15 minutes
        Instant shortExpiry = now.plusSeconds(12);

        scheduler.schedule(101L, longExpiry);
        scheduler.schedule(202L, shortExpiry);

        assertThat(amqpAdmin.declaredQueues).hasSize(2);

        Queue longQueue = amqpAdmin.declaredQueues.get(0);
        Queue shortQueue = amqpAdmin.declaredQueues.get(1);

        assertThat(longQueue.getName())
                .isEqualTo(RabbitReservationExpiryConfiguration.delayQueueName(101L, longExpiry.toEpochMilli()));
        assertThat(shortQueue.getName())
                .isEqualTo(RabbitReservationExpiryConfiguration.delayQueueName(202L, shortExpiry.toEpochMilli()));
        assertThat(longQueue.getName()).isNotEqualTo(shortQueue.getName());

        long longTtl = ((Number) longQueue.getArguments().get("x-message-ttl")).longValue();
        long shortTtl = ((Number) shortQueue.getArguments().get("x-message-ttl")).longValue();
        assertThat(longTtl).isGreaterThan(800_000L);
        assertThat(shortTtl).isLessThan(20_000L);
        assertThat(shortTtl).isLessThan(longTtl);

        assertThat(longQueue.getArguments().get("x-dead-letter-exchange"))
                .isEqualTo(RabbitReservationExpiryConfiguration.EXPIRY_EXCHANGE);
        assertThat(shortQueue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo(RabbitReservationExpiryConfiguration.EXPIRY_ROUTING_KEY);

        // Empty delay queues must auto-delete after TTL + grace, otherwise they leak.
        assertThat(((Number) longQueue.getArguments().get("x-expires")).longValue())
                .isEqualTo(longTtl + RabbitReservationExpiryScheduler.QUEUE_EXPIRES_GRACE_MS);
        assertThat(((Number) shortQueue.getArguments().get("x-expires")).longValue())
                .isEqualTo(shortTtl + RabbitReservationExpiryScheduler.QUEUE_EXPIRES_GRACE_MS);
        assertThat(longQueue.isDurable()).isTrue();
        assertThat(longQueue.isExclusive()).isFalse();
        assertThat(longQueue.isAutoDelete()).isFalse();

        // Messages published to default exchange with routing key = private queue name (not shared FIFO).
        assertThat(rabbitTemplate.sends).hasSize(2);
        assertThat(rabbitTemplate.sends.get(0).exchange()).isEmpty();
        assertThat(rabbitTemplate.sends.get(0).routingKey()).isEqualTo(longQueue.getName());
        assertThat(rabbitTemplate.sends.get(0).body()).isEqualTo("101");
        assertThat(rabbitTemplate.sends.get(1).routingKey()).isEqualTo(shortQueue.getName());
        assertThat(rabbitTemplate.sends.get(1).body()).isEqualTo("202");

        // Must not use legacy shared delay routing.
        assertThat(rabbitTemplate.sends)
                .noneMatch(s -> RabbitReservationExpiryConfiguration.LEGACY_SHARED_DELAY_QUEUE.equals(s.routingKey()));
    }

    @Test
    void delayQueueNameEncodesReservationAndExpiry() {
        assertThat(RabbitReservationExpiryConfiguration.delayQueueName(7L, 1_700_000_000_000L))
                .isEqualTo("labops.reservation.expiry.delay.7.1700000000000");
    }

    private record Send(String exchange, String routingKey, String body) {
    }

    private static final class CapturingRabbitTemplate extends RabbitTemplate {
        private final List<Send> sends = new ArrayList<>();

        CapturingRabbitTemplate() {
            super((ConnectionFactory) null);
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object object) {
            sends.add(new Send(exchange, routingKey, String.valueOf(object)));
        }
    }

    private static final class RecordingAmqpAdmin implements AmqpAdmin {
        private final List<Queue> declaredQueues = new ArrayList<>();
        private final Map<String, Queue> byName = new ConcurrentHashMap<>();

        @Override
        public void declareExchange(Exchange exchange) {
        }

        @Override
        public boolean deleteExchange(String exchangeName) {
            return false;
        }

        @Override
        public Queue declareQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String declareQueue(Queue queue) {
            declaredQueues.add(queue);
            byName.put(queue.getName(), queue);
            return queue.getName();
        }

        @Override
        public boolean deleteQueue(String queueName) {
            return byName.remove(queueName) != null;
        }

        @Override
        public void deleteQueue(String queueName, boolean unused, boolean empty) {
        }

        @Override
        public void purgeQueue(String queueName, boolean noWait) {
        }

        @Override
        public int purgeQueue(String queueName) {
            return 0;
        }

        @Override
        public void declareBinding(Binding binding) {
        }

        @Override
        public void removeBinding(Binding binding) {
        }

        @Override
        public Properties getQueueProperties(String queueName) {
            return null;
        }

        @Override
        public QueueInformation getQueueInfo(String queueName) {
            return null;
        }
    }
}
