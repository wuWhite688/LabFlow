package com.arthur.labops.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {

    Optional<PaymentRequest> findByIdempotencyKey(String idempotencyKey);

    List<PaymentRequest> findByOrderNoOrderByIdAsc(String orderNo);

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
