package com.arthur.labops.payment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    Optional<PaymentOrder> findByReservationId(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.orderNo = :orderNo")
    Optional<PaymentOrder> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.reservationId = :reservationId")
    Optional<PaymentOrder> findByReservationIdForUpdate(@Param("reservationId") Long reservationId);

    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.orderNo in :orderNos")
    List<PaymentOrder> findAllByOrderNoIn(@Param("orderNos") List<String> orderNos);
}
