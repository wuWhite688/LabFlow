package com.arthur.labops.reservation.expiry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ topology names for reservation expiry. Overridable in tests so HOL
 * integration tests do not share the production work queue consumer.
 */
@ConfigurationProperties(prefix = "labops.reservation-expiry.rabbit")
public class RabbitExpiryTopologyProperties {

    public static final String DEFAULT_DELAY_QUEUE_PREFIX = "labops.reservation.expiry.delay.";
    public static final String DEFAULT_PAYMENT_DELAY_QUEUE_PREFIX = "labops.reservation.payment.delay.";
    public static final String DEFAULT_EXPIRY_EXCHANGE = "labops.reservation.expiry.exchange";
    public static final String DEFAULT_EXPIRY_QUEUE = "labops.reservation.expiry.queue";
    public static final String DEFAULT_EXPIRY_ROUTING_KEY = "reservation.expire";
    public static final String LEGACY_SHARED_DELAY_QUEUE = "labops.reservation.expiry.delay.queue";
    public static final String LEGACY_DELAY_EXCHANGE = "labops.reservation.expiry.delay.exchange";

    private String delayQueuePrefix = DEFAULT_DELAY_QUEUE_PREFIX;
    private String paymentDelayQueuePrefix = DEFAULT_PAYMENT_DELAY_QUEUE_PREFIX;
    private String expiryExchange = DEFAULT_EXPIRY_EXCHANGE;
    private String expiryQueue = DEFAULT_EXPIRY_QUEUE;
    private String expiryRoutingKey = DEFAULT_EXPIRY_ROUTING_KEY;

    public String getDelayQueuePrefix() {
        return delayQueuePrefix;
    }

    public void setDelayQueuePrefix(String delayQueuePrefix) {
        this.delayQueuePrefix = delayQueuePrefix;
    }

    public String getPaymentDelayQueuePrefix() {
        return paymentDelayQueuePrefix;
    }

    public void setPaymentDelayQueuePrefix(String paymentDelayQueuePrefix) {
        this.paymentDelayQueuePrefix = paymentDelayQueuePrefix;
    }

    public String getExpiryExchange() {
        return expiryExchange;
    }

    public void setExpiryExchange(String expiryExchange) {
        this.expiryExchange = expiryExchange;
    }

    public String getExpiryQueue() {
        return expiryQueue;
    }

    public void setExpiryQueue(String expiryQueue) {
        this.expiryQueue = expiryQueue;
    }

    public String getExpiryRoutingKey() {
        return expiryRoutingKey;
    }

    public void setExpiryRoutingKey(String expiryRoutingKey) {
        this.expiryRoutingKey = expiryRoutingKey;
    }

    public String delayQueueName(long reservationId, long expiresAtEpochMs) {
        return delayQueueName(ReservationDeadlineKind.APPROVAL, reservationId, expiresAtEpochMs);
    }

    /**
     * Approval and payment deadlines get disjoint queue namespaces. They share the
     * dead-letter work queue, but a reservation can legitimately hold one of each
     * over its lifetime, and cancelling one must not delete the other's queue.
     */
    public String delayQueueName(ReservationDeadlineKind kind, long reservationId, long deadlineEpochMs) {
        String prefix = kind == ReservationDeadlineKind.PAYMENT ? paymentDelayQueuePrefix : delayQueuePrefix;
        return prefix + reservationId + "." + deadlineEpochMs;
    }
}
