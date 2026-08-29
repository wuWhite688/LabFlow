package com.arthur.labops.user;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from PlatformUser user where user.id = :id")
    Optional<PlatformUser> findByIdForUpdate(@Param("id") Long id);

    boolean existsByUsername(String username);

    long countByUsernameNot(String username);

    List<PlatformUser> findByRoleAndEnabledTrueOrderByDisplayName(UserRole role);
}
