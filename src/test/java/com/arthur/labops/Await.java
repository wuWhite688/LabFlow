package com.arthur.labops;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Outbound channel calls are dispatched on a pool thread, so a test that asserts
 * on their effect immediately after the triggering request is asserting on a
 * race. Poll instead.
 */
final class Await {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private Await() {
    }

    static void until(String description, BooleanSupplier condition) {
        until(description, DEFAULT_TIMEOUT, condition);
    }

    static void until(String description, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + description);
            }
        }
        fail("Timed out after " + timeout + " waiting for: " + description);
    }

    /**
     * Gives an asynchronous side effect time to happen, for assertions that
     * something must <em>not</em> happen. Without this a "did not happen" check
     * can pass simply by running first.
     */
    static void settle() {
        try {
            TimeUnit.MILLISECONDS.sleep(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
