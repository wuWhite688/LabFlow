package com.arthur.labops.reservation;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    boolean existsByEquipmentIdAndStatusInAndStartTimeLessThanAndEndTimeGreaterThan(
            Long equipmentId,
            Collection<ReservationStatus> statuses,
            Instant requestedEnd,
            Instant requestedStart);

    @Query("""
            select case when count(reservation) > 0 then true else false end
            from Reservation reservation
            where reservation.equipment.id = :equipmentId
              and reservation.status in :statuses
              and reservation.startTime < :requestedEnd
              and reservation.endTime > :requestedStart
              and reservation.id <> :excludeId
            """)
    boolean existsConflictExcludingId(
            @Param("equipmentId") Long equipmentId,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("requestedEnd") Instant requestedEnd,
            @Param("requestedStart") Instant requestedStart,
            @Param("excludeId") Long excludeId);

    boolean existsByEquipmentIdAndStatusAndStartTimeLessThanEqualAndEndTimeGreaterThan(
            Long equipmentId,
            ReservationStatus status,
            Instant startBoundary,
            Instant endBoundary);

    @Query("select reservation.equipment.id from Reservation reservation where reservation.id = :id")
    java.util.Optional<Long> findEquipmentIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from Reservation reservation where reservation.id = :id")
    java.util.Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select reservation.id from Reservation reservation
            where reservation.status = com.arthur.labops.reservation.ReservationStatus.PENDING
              and reservation.expiresAt <= :now
            order by reservation.id
            """)
    java.util.List<Long> findOverduePendingIds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from Reservation reservation
            where reservation.equipment.id = :equipmentId
              and reservation.status in :statuses
            order by reservation.id
            """)
    java.util.List<Reservation> findByEquipmentIdAndStatusInForUpdate(
            @Param("equipmentId") Long equipmentId,
            @Param("statuses") Collection<ReservationStatus> statuses);

    long countByRequesterIdAndStatusIn(Long requesterId, Collection<ReservationStatus> statuses);

    long countByStatus(ReservationStatus status);

    long countByStartTimeBetween(Instant start, Instant end);

    @Query("""
            select distinct reservation.equipment.id from Reservation reservation
            where reservation.status = com.arthur.labops.reservation.ReservationStatus.APPROVED
            order by reservation.equipment.id
            """)
    java.util.List<Long> findEquipmentIdsWithApprovedReservations();
}
