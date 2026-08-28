package com.arthur.labops.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findByOrderNoOrderByIdAsc(String orderNo);

    long countByOrderNo(String orderNo);

    @Query("""
            select transaction from PaymentTransaction transaction
            where transaction.occurredAt >= :from and transaction.occurredAt < :to
            order by transaction.id
            """)
    List<PaymentTransaction> findByOccurredAtWindow(@Param("from") Instant from, @Param("to") Instant to);
}
