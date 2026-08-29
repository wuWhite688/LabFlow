package com.arthur.labops.auth;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select family from RefreshTokenFamily family where family.id = :id")
    Optional<RefreshTokenFamily> findByIdForUpdate(@Param("id") Long id);
}
