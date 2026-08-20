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
        evictEmptyWindows(now);
        // Read existing buckets only. Creating a per-username key here would let
        // an IP-capped client grow the map by rotating usernames on 429s.
        if (snapshotCount(ipKey(clientIp), now) >= maxPerIp
                || snapshotCount(identityKey(clientIp, username), now) >= maxPerIdentity) {
            throw new BusinessException(
                    "LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    public void recordFailure(String clientIp, String username) {
        long now = System.currentTimeMillis();
        record(identityKey(clientIp, username), now);
        record(ipKey(clientIp), now);
    }

    public void recordSuccess(String clientIp, String username) {
        windows.remove(identityKey(clientIp, username));
        windows.computeIfPresent(ipKey(clientIp), (key, window) -> {
            int remaining = window.decay(System.currentTimeMillis(), windowMs);
            return remaining == 0 ? null : window;
        });
    }

    int windowCount() {
        return windows.size();
    }

    private void record(String key, long now) {
        windows.compute(key, (ignored, existing) -> {
            AttemptWindow window = existing != null ? existing : new AttemptWindow();
            window.add(now, windowMs);
            return window;
        });
    }

    private int snapshotCount(String key, long now) {
        int[] count = {0};
        windows.computeIfPresent(key, (ignored, window) -> {
            int remaining = window.pruneAndCount(now, windowMs);
            count[0] = remaining;
            return remaining == 0 ? null : window;
        });
        return count[0];
    }

    private void evictEmptyWindows(long now) {
        for (String key : windows.keySet()) {
            snapshotCount(key, now);
        }
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

        synchronized int decay(long now, long windowMs) {
            pruneAndCount(now, windowMs);
            if (!times.isEmpty()) {
                times.pollFirst();
            }
            return times.size();
        }
    }
}
