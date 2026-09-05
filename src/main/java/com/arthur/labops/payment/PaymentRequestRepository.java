package com.arthur.labops.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    Optional<PaymentRequest> findByIdempotencyKey(String idempotencyKey);

    /**
     * The same lookup, holding a row lock until the transaction commits.
     *
     * <p>Every path that reads state, decides on it, and then writes must use this
     * one. Under REPEATABLE READ a plain read keeps its snapshot, so a guard like
     * {@link PaymentRequest#matchesChannelKey(String)} would be checked against a
     * channel attempt that another transaction has already advanced — and because
     * Hibernate updates every column, the stale transaction would then write the
     * old {@code channel_attempt} back over the new one.
     *
     * <p>Take this lock <em>before</em> loading the entity any other way in the
     * same persistence context: a locking query returns the instance already in
     * the first-level cache without refreshing its fields, so the lock would be
     * held over values that are still stale.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from PaymentRequest request where request.idempotencyKey = :idempotencyKey")
    Optional<PaymentRequest> findByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    List<PaymentRequest> findByOrderNoOrderByIdAsc(String orderNo);

    /**
     * Every outbound request of an order, locked, in id order.
     *
     * <p>The callback path has to pick the request whose current channel key the
     * rejection names, and that choice is only sound if no one can advance the key
     * between the read and the write. Ordering by id keeps multi-row locking in a
     * fixed sequence so two callbacks on the same order cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from PaymentRequest request where request.orderNo = :orderNo order by request.id")
    List<PaymentRequest> findByOrderNoForUpdateOrderByIdAsc(@Param("orderNo") String orderNo);

    /**
     * The one live outbound request of this kind for an order, used to correlate a
     * channel's rejection back to the intent it was rejecting. Correlating on
     * order and type rather than on the callback's key is deliberate: the key the
     * channel echoes is the key of the attempt, and the whole point of reopening
     * is that the next attempt needs a different one.
     */
    Optional<PaymentRequest> findFirstByOrderNoAndTypeAndStatusOrderByIdDesc(
            String orderNo, PaymentTransactionType type, PaymentRequestStatus status);

    /**
     * Requests still owed to the channel and due for another attempt. ABANDONED is
     * excluded — those already have a ticket and a human.
     */
    @Query("""
            select request.idempotencyKey from PaymentRequest request
            where request.status in (
                    com.arthur.labops.payment.PaymentRequestStatus.PENDING,
                    com.arthur.labops.payment.PaymentRequestStatus.FAILED)
              and request.nextAttemptAt <= :now
            order by request.id
            """)
    List<String> findDueKeys(@Param("now") Instant now);
}
