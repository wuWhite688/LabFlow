package com.arthur.labops.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;

import jakarta.persistence.OptimisticLockException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_REFRESH_MESSAGE = "刷新令牌无效或已失效";

    private final AuthenticationManager authenticationManager;
    private final PlatformUserRepository userRepository;
    private final AuthRefreshTx authRefreshTx;
    private final LoginAttemptGuard loginAttemptGuard;

    public AuthService(AuthenticationManager authenticationManager,
                       PlatformUserRepository userRepository,
                       AuthRefreshTx authRefreshTx,
                       LoginAttemptGuard loginAttemptGuard) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.authRefreshTx = authRefreshTx;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    @Transactional
    public IssuedAuthSession login(LoginRequest request, String clientIp) {
        String username = request.username().trim();
        loginAttemptGuard.assertAllowed(clientIp, username);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (BadCredentialsException exception) {
            loginAttemptGuard.recordFailure(clientIp, username);
            throw new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        } catch (AuthenticationException exception) {
            loginAttemptGuard.recordFailure(clientIp, username);
            throw new BusinessException("AUTHENTICATION_FAILED", "登录失败", HttpStatus.UNAUTHORIZED);
        }

        // No enabled check here: DatabaseUserDetailsService maps enabled onto
        // UserDetails, so Spring Security's pre-authentication checks reject a
        // disabled account with DisabledException *before* the password is even
        // compared — it lands in the AuthenticationException catch above. Callers
        // therefore see AUTHENTICATION_FAILED for both a disabled account and a
        // wrong password, which is the behaviour we want: it does not reveal that
        // the account exists and the supplied password was correct.
        PlatformUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        loginAttemptGuard.recordSuccess(clientIp, username);
        return authRefreshTx.issueLoginSession(user);
    }

    public IssuedAuthSession refresh(String rawRefreshToken) {
        String raw = requireRefreshToken(rawRefreshToken);
        try {
            return mapOutcome(authRefreshTx.refreshInTx(raw));
        } catch (RuntimeException exception) {
            if (!isLockFailure(exception)) {
                throw exception;
            }
            log.info("refresh lock conflict, retrying once");
            try {
                return mapOutcome(authRefreshTx.refreshInTx(raw));
            } catch (RuntimeException retry) {
                if (!isLockFailure(retry)) {
                    throw retry;
                }
                return mapOutcome(terminateAfterLockFailure(raw));
            }
        }
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String raw = rawRefreshToken.trim();
        try {
            authRefreshTx.logoutInTx(raw);
        } catch (RuntimeException exception) {
            if (!isLockFailure(exception)) {
                throw exception;
            }
            try {
                authRefreshTx.logoutInTx(raw);
            } catch (RuntimeException retry) {
                if (!isLockFailure(retry)) {
                    throw retry;
                }
            }
        }
    }

    private RefreshOutcome terminateAfterLockFailure(String raw) {
        try {
            return authRefreshTx.terminateLiveFamilyInTx(raw);
        } catch (RuntimeException exception) {
            if (!isLockFailure(exception)) {
                throw exception;
            }
            try {
                return authRefreshTx.terminateLiveFamilyInTx(raw);
            } catch (RuntimeException retry) {
                if (isLockFailure(retry)) {
                    return RefreshOutcome.invalid();
                }
                throw retry;
            }
        }
    }

    private IssuedAuthSession mapOutcome(RefreshOutcome outcome) {
        return switch (outcome.kind()) {
            case ISSUED -> outcome.session();
            case REUSED, INVALID -> throw invalidRefresh();
            case DISABLED -> throw new BusinessException("USER_DISABLED", "账号已停用", HttpStatus.UNAUTHORIZED);
        };
    }

    private String requireRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalidRefresh();
        }
        return rawRefreshToken.trim();
    }

    private static BusinessException invalidRefresh() {
        return new BusinessException("INVALID_REFRESH_TOKEN", INVALID_REFRESH_MESSAGE, HttpStatus.UNAUTHORIZED);
    }

    static boolean isLockFailure(Throwable error) {
        while (error != null) {
            if (error instanceof PessimisticLockingFailureException
                    || error instanceof OptimisticLockingFailureException
                    || error instanceof OptimisticLockException) {
                return true;
            }
            error = error.getCause();
        }
        return false;
    }

    static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash refresh token", exception);
        }
    }
}
