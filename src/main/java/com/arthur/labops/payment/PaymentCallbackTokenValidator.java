package com.arthur.labops.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Production must never fall back to the baked-in simulated-channel token.
 * The callback endpoint is unauthenticated in the Spring Security sense; the
 * shared token is the only thing standing between the ledger and anyone who
 * can reach {@code POST /api/payments/callback}.
 */
public final class PaymentCallbackTokenValidator {

    static final int MIN_TOKEN_BYTES = 16;

    static final String DEMO_TOKEN = "labflow-simulated-channel";

    private static final Set<String> BANNED_EXACT = Set.of(
            DEMO_TOKEN,
            "change-me-callback-token",
            "REPLACE_ME_with_openssl_rand_hex_16_or_longer_token"
    );

    private PaymentCallbackTokenValidator() {
    }

    /**
     * Enforced when spring profile {@code production} is active, and when the
     * process binds a non-loopback address.
     */
    public static void requireProductionToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Production payment callback token is missing. Set PAYMENT_CALLBACK_TOKEN in .env "
                            + "(labops.payment.callback-token) to a random value of at least "
                            + MIN_TOKEN_BYTES + " bytes.");
        }
        String trimmed = token.trim();
        int bytes = trimmed.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_TOKEN_BYTES) {
            throw new IllegalStateException(
                    "Production payment callback token is too short (" + bytes + " bytes). "
                            + "Require at least " + MIN_TOKEN_BYTES + " bytes.");
        }
        if (isPlaceholder(trimmed)) {
            throw new IllegalStateException(
                    "Production payment callback token looks like a placeholder. "
                            + "Set PAYMENT_CALLBACK_TOKEN in .env to a unique random token "
                            + "(do not use the simulated-channel default or .env.example values).");
        }
    }

    /**
     * Constant-time compare of the configured token and the header value.
     * {@code String.equals} leaks prefix length; {@link MessageDigest#isEqual}
     * does not. Null on either side is a miss — {@code getBytes} would NPE, and
     * a missing {@code X-Channel-Token} must not match an empty configured token.
     *
     * <p>The configured side is trimmed, exactly as {@link #requireProductionToken}
     * trims it before validating. Otherwise a token with a stray trailing space or
     * newline from {@code .env} passes the startup check and then rejects every
     * callback. A configured token that is blank after trimming matches nothing:
     * an empty header must never authenticate against an empty configuration.
     */
    public static boolean matchesPresentedToken(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        String configured = expected.trim();
        if (configured.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isPlaceholder(String token) {
        if (BANNED_EXACT.contains(token)) {
            return true;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.contains("change-me")
                || lower.contains("change_me")
                || lower.contains("replace_me")
                || lower.contains("replace-me")
                || lower.contains("simulated-channel")
                || lower.contains("placeholder");
    }
}
