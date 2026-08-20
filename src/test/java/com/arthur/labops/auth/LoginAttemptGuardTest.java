package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.arthur.labops.common.BusinessException;

class LoginAttemptGuardTest {

    @Test
    void rotatingUsernamesAfterIpCapDoesNotGrowBuckets() {
        LoginAttemptGuard guard = new LoginAttemptGuard(5, 3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            guard.assertAllowed("203.0.113.50", "seed-" + i);
            guard.recordFailure("203.0.113.50", "seed-" + i);
        }
        int bucketsAtCap = guard.windowCount();
        assertThat(bucketsAtCap).isEqualTo(4);

        for (int i = 0; i < 200; i++) {
            assertRateLimited(guard, "203.0.113.50", "rotate-" + i);
        }
        assertThat(guard.windowCount()).isEqualTo(bucketsAtCap);
    }

    @Test
    void identityLimitStillRejectsSameUsername() {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, 20, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            guard.assertAllowed("203.0.113.51", "teacher");
            guard.recordFailure("203.0.113.51", "teacher");
        }
        assertRateLimited(guard, "203.0.113.51", "TEACHER");
        assertRateLimited(guard, "203.0.113.51", " teacher ");
    }

    @Test
    void ipLimitStillRejectsOtherUsernames() {
        LoginAttemptGuard guard = new LoginAttemptGuard(5, 3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            guard.assertAllowed("203.0.113.52", "user-" + i);
            guard.recordFailure("203.0.113.52", "user-" + i);
        }
        assertRateLimited(guard, "203.0.113.52", "someone-else");
    }

    @Test
    void expiredWindowsAreDroppedAndCountingResumes() throws Exception {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 2, Duration.ofMillis(80));
        guard.assertAllowed("203.0.113.53", "student");
        guard.recordFailure("203.0.113.53", "student");
        guard.assertAllowed("203.0.113.53", "other");
        guard.recordFailure("203.0.113.53", "other");
        assertRateLimited(guard, "203.0.113.53", "later");
        assertThat(guard.windowCount()).isGreaterThan(0);

        Thread.sleep(120);

        assertThatCode(() -> guard.assertAllowed("203.0.113.53", "later"))
                .doesNotThrowAnyException();
        assertThat(guard.windowCount()).isZero();
        guard.recordFailure("203.0.113.53", "later");
        assertThat(guard.windowCount()).isEqualTo(2);
    }

    @Test
    void successfulLoginRemovesIdentityAndDecaysIp() {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, 20, Duration.ofHours(1));
        guard.assertAllowed("203.0.113.54", "student");
        guard.recordFailure("203.0.113.54", "student");
        guard.assertAllowed("203.0.113.54", "student");
        guard.recordFailure("203.0.113.54", "student");
        guard.recordSuccess("203.0.113.54", "student");

        guard.assertAllowed("203.0.113.54", "student");
        guard.recordFailure("203.0.113.54", "student");
        guard.assertAllowed("203.0.113.54", "student");
        guard.recordFailure("203.0.113.54", "student");
        guard.assertAllowed("203.0.113.54", "student");
    }

    private static void assertRateLimited(LoginAttemptGuard guard, String ip, String username) {
        assertThatThrownBy(() -> guard.assertAllowed(ip, username))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo("LOGIN_RATE_LIMITED");
    }
}
