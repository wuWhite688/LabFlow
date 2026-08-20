package com.arthur.labops.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.arthur.labops.common.BusinessException;

/**
 * In-memory login throttle. Keys are IP+username and IP, so one client cannot
 * lock a victim on another IP, and rotating usernames still hits the IP cap.
 * Fail-open is not needed: this never talks to Redis.
 */
@Component
public class LoginAttemptGuard {

    private final int maxPerIdentity;
    private final int maxPerIp;
    private final long windowMs;
    private final ConcurrentHashMap<String, AttemptWindow> windows = new ConcurrentHashMap<>();

    public LoginAttemptGuard(
            @Value("${labops.login.max-failures-per-identity:5}") int maxPerIdentity,
            @Value("${labops.login.max-failures-per-ip:20}") int maxPerIp,
            @Value("${labops.login.window:15m}") Duration window) {
        this.maxPerIdentity = maxPerIdentity;
        this.maxPerIp = maxPerIp;
        this.windowMs = window.toMillis();
    }

    public void assertAllowed(String clientIp, String username) {
        long now = System.currentTimeMillis();
        int identityCount = window(identityKey(clientIp, username)).pruneAndCount(now, windowMs);
        int ipCount = window(ipKey(clientIp)).pruneAndCount(now, windowMs);
        if (identityCount >= maxPerIdentity || ipCount >= maxPerIp) {
            throw new BusinessException(
                    "LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void recordFailure(String clientIp, String username) {
        long now = System.currentTimeMillis();
        window(identityKey(clientIp, username)).add(now, windowMs);
        window(ipKey(clientIp)).add(now, windowMs);
    }

    public void recordSuccess(String clientIp, String username) {
        windows.remove(identityKey(clientIp, username));
        AttemptWindow ipWindow = windows.get(ipKey(clientIp));
        if (ipWindow != null) {
            ipWindow.decay(System.currentTimeMillis(), windowMs);
        }
    }

    private AttemptWindow window(String key) {
        return windows.computeIfAbsent(key, ignored -> new AttemptWindow());
    }

    static String identityKey(String clientIp, String username) {
        return ipKey(clientIp) + "|" + username.trim().toLowerCase(Locale.ROOT);
    }

    static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "ip:unknown";
        }
        return "ip:" + clientIp.trim();
    }

    private static final class AttemptWindow {
        private final ArrayDeque<Long> times = new ArrayDeque<>();

        synchronized int pruneAndCount(long now, long windowMs) {
            while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
                times.pollFirst();
            }
            return times.size();
        }

        synchronized void add(long now, long windowMs) {
            pruneAndCount(now, windowMs);
            times.addLast(now);
        }

        synchronized void decay(long now, long windowMs) {
            pruneAndCount(now, windowMs);
            if (!times.isEmpty()) {
                times.pollFirst();
            }
        }
    }
}
