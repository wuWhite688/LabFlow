package com.arthur.labops.auth;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("select token.family.id from RefreshToken token where token.tokenHash = :tokenHash")
    Optional<Long> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token
            from RefreshToken token
            where token.tokenHash = :tokenHash
            """)
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("""
            select (count(token) > 0)
              from RefreshToken token
             where token.family.id = :familyId
               and token.revokedAt is null
               and token.expiresAt > :now
            """)
    boolean existsActiveByFamilyId(@Param("familyId") Long familyId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken token
               set token.revokedAt = :now, token.revokeReason = :reason
             where token.family.id = :familyId
               and token.revokedAt is null
            """)
    int revokeActiveByFamilyId(
            @Param("familyId") Long familyId,
            @Param("now") Instant now,
            @Param("reason") RefreshTokenRevokeReason reason);
}
