package com.arthur.labops.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.user.CurrentUserResponse;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final PlatformUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(AuthenticationManager authenticationManager,
                       PlatformUserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public IssuedAuthSession login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username().trim(), request.password()));
        } catch (BadCredentialsException exception) {
            throw new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        } catch (AuthenticationException exception) {
            throw new BusinessException("AUTHENTICATION_FAILED", "登录失败", HttpStatus.UNAUTHORIZED);
        }

        PlatformUser user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(() -> new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        if (!user.isEnabled()) {
            throw new BusinessException("USER_DISABLED", "账号已停用", HttpStatus.UNAUTHORIZED);
        }
        return issueTokens(user);
    }

    @Transactional
    public IssuedAuthSession refresh(String rawRefreshToken) {
        String raw = requireRefreshToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHashForUpdate(hashToken(raw))
                .orElseThrow(() -> new BusinessException(
                        "INVALID_REFRESH_TOKEN", "刷新令牌无效或已失效", HttpStatus.UNAUTHORIZED));

        Instant now = Instant.now();
        if (!stored.isActive(now)) {
            throw new BusinessException(
                    "INVALID_REFRESH_TOKEN", "刷新令牌无效或已失效", HttpStatus.UNAUTHORIZED);
        }

        PlatformUser user = stored.getUser();
        if (!user.isEnabled()) {
            stored.revoke(now);
            throw new BusinessException("USER_DISABLED", "账号已停用", HttpStatus.UNAUTHORIZED);
        }

        // Rotation: revoke old refresh token, issue a new pair.
        stored.revoke(now);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String raw = rawRefreshToken.trim();
        refreshTokenRepository.findByTokenHashForUpdate(hashToken(raw)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.revoke(Instant.now());
            }
        });
    }

    private IssuedAuthSession issueTokens(PlatformUser user) {
        String accessToken = jwtService.createAccessToken(user);
        String rawRefresh = generateRefreshToken();
        Instant refreshExp = Instant.now().plus(jwtProperties.getRefreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user, hashToken(rawRefresh), refreshExp));
        AuthResponse response = AuthResponse.of(
                accessToken,
                jwtService.accessTokenTtlSeconds(),
                CurrentUserResponse.from(user));
        return new IssuedAuthSession(response, rawRefresh);
    }

    private String requireRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(
                    "INVALID_REFRESH_TOKEN", "刷新令牌无效或已失效", HttpStatus.UNAUTHORIZED);
        }
        return rawRefreshToken.trim();
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
