package com.arthur.labops.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.user.CurrentUserResponse;
import com.arthur.labops.user.PlatformUser;

/**
 * Refresh/logout token-state transactions live here. {@code AuthService.login}
 * keeps its existing outer {@code @Transactional}; this bean is not the only
 * auth writer. Lock order is family row then the presented token row; sibling
 * revocation uses a bulk UPDATE while holding the family lock. Refresh/logout
 * callers must map {@link RefreshOutcome} to HTTP only after this bean returns
 * so reuse/disable writes are already committed.
 */
@Service
public class AuthRefreshTx {

    private static final Logger log = LoggerFactory.getLogger(AuthRefreshTx.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenFamilyRepository familyRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthRefreshTx(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenFamilyRepository familyRepository,
            JwtService jwtService,
            JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.familyRepository = familyRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public IssuedAuthSession issueLoginSession(PlatformUser user) {
        RefreshTokenFamily family = familyRepository.save(new RefreshTokenFamily(user));
        return issueInFamily(family, user, Instant.now());
    }

    @Transactional
    public RefreshOutcome refreshInTx(String rawRefreshToken) {
        LockedRefresh locked = lockPresented(rawRefreshToken);
        if (locked == null) {
            return RefreshOutcome.invalid();
        }
        Instant now = Instant.now();
        return switch (reuseDecision(locked.family(), locked.token(), now)) {
            case SKIP -> RefreshOutcome.invalid();
            case COMPROMISE -> {
                compromiseFamily(locked.family(), locked.token(), now);
                yield RefreshOutcome.reused();
            }
            case PRESENTED_STILL_ACTIVE -> rotateIfEnabled(locked.family(), locked.token(), now);
        };
    }

    @Transactional
    public RefreshOutcome terminateLiveFamilyInTx(String rawRefreshToken) {
        LockedRefresh locked = lockPresented(rawRefreshToken);
        if (locked == null) {
            return RefreshOutcome.invalid();
        }
        Instant now = Instant.now();
        return switch (reuseDecision(locked.family(), locked.token(), now)) {
            case SKIP -> RefreshOutcome.invalid();
            case COMPROMISE, PRESENTED_STILL_ACTIVE -> {
                compromiseFamily(locked.family(), locked.token(), now);
                yield RefreshOutcome.reused();
            }
        };
    }

    @Transactional
    public void logoutInTx(String rawRefreshToken) {
        Long familyId = refreshTokenRepository.findFamilyIdByTokenHash(AuthService.hashToken(rawRefreshToken.trim()))
                .orElse(null);
        if (familyId == null) {
            return;
        }
        familyRepository.findByIdForUpdate(familyId).ifPresent(family ->
                terminateFamily(family, RefreshTokenRevokeReason.LOGOUT, Instant.now()));
    }

    private LockedRefresh lockPresented(String rawRefreshToken) {
        String hash = AuthService.hashToken(rawRefreshToken.trim());
        Long familyId = refreshTokenRepository.findFamilyIdByTokenHash(hash).orElse(null);
        if (familyId == null) {
            return null;
        }
        RefreshTokenFamily family = familyRepository.findByIdForUpdate(familyId).orElse(null);
        if (family == null) {
            return null;
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHashForUpdate(hash).orElse(null);
        if (stored == null || !familyId.equals(stored.getFamily().getId())) {
            return null;
        }
        return new LockedRefresh(family, stored);
    }

    private ReuseDecision reuseDecision(RefreshTokenFamily family, RefreshToken stored, Instant now) {
        if (family.isTerminal()) {
            return ReuseDecision.SKIP;
        }
        RefreshTokenRevokeReason reason = stored.getRevokeReason();
        if (reason == RefreshTokenRevokeReason.ROTATED) {
            if (refreshTokenRepository.existsActiveByFamilyId(family.getId(), now)) {
                return ReuseDecision.COMPROMISE;
            }
            return ReuseDecision.SKIP;
        }
        if (reason == RefreshTokenRevokeReason.LOGOUT
                || reason == RefreshTokenRevokeReason.USER_DISABLED
                || reason == RefreshTokenRevokeReason.REUSE) {
            return ReuseDecision.SKIP;
        }
        if (!stored.getExpiresAt().isAfter(now)) {
            return ReuseDecision.SKIP;
        }
        return ReuseDecision.PRESENTED_STILL_ACTIVE;
    }

    private RefreshOutcome rotateIfEnabled(RefreshTokenFamily family, RefreshToken stored, Instant now) {
        PlatformUser user = stored.getUser();
        if (!user.isEnabled()) {
            terminateFamily(family, RefreshTokenRevokeReason.USER_DISABLED, now);
            return RefreshOutcome.disabled();
        }
        stored.revoke(now, RefreshTokenRevokeReason.ROTATED);
        return RefreshOutcome.issued(issueInFamily(family, user, now));
    }

    private IssuedAuthSession issueInFamily(RefreshTokenFamily family, PlatformUser user, Instant now) {
        String rawRefresh = generateRefreshToken();
        Instant refreshExp = now.plus(jwtProperties.getRefreshTokenTtl());
        RefreshToken token = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, family, AuthService.hashToken(rawRefresh), refreshExp));
        family.setCurrentTokenId(token.getId());
        AuthResponse response = AuthResponse.of(
                jwtService.createAccessToken(user),
                jwtService.accessTokenTtlSeconds(),
                CurrentUserResponse.from(user));
        return new IssuedAuthSession(response, rawRefresh);
    }

    private void compromiseFamily(RefreshTokenFamily family, RefreshToken presented, Instant now) {
        Long presentedId = presented.getId();
        Long currentTokenId = family.getCurrentTokenId();
        Long userId = family.getUser().getId();
        Long familyId = family.getId();
        terminateFamily(family, RefreshTokenRevokeReason.REUSE, now);
        log.warn(
                "refresh token reused: familyId={} userId={} presentedTokenId={} currentTokenId={}",
                familyId, userId, presentedId, currentTokenId);
    }

    private void terminateFamily(RefreshTokenFamily family, RefreshTokenRevokeReason reason, Instant now) {
        if (family.isTerminal()) {
            return;
        }
        family.terminate(now, reason);
        refreshTokenRepository.revokeActiveByFamilyId(family.getId(), now, reason);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private enum ReuseDecision {
        SKIP,
        COMPROMISE,
        PRESENTED_STILL_ACTIVE
    }

    private record LockedRefresh(RefreshTokenFamily family, RefreshToken token) {
    }
}
