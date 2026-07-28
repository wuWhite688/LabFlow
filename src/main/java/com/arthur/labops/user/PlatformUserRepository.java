package com.arthur.labops.user;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

    Optional<PlatformUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<PlatformUser> findByRoleAndEnabledTrueOrderByDisplayName(UserRole role);
}
