package com.arthur.labops.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Production must never fall back to baked-in demo secrets.
 * Access tokens are HS256-signed; secret must be long enough and non-placeholder.
 */
public final class JwtSecretValidator {

    static final int MIN_SECRET_BYTES = 32;

    private static final Set<String> BANNED_EXACT = Set.of(
            "labflow-dev-only-change-me-32bytes-min!!",
            "labflow-prod-change-me-use-env-32b-min!!",
            "replace-with-long-random-secret-at-least-32-bytes",
            "REPLACE_ME_with_openssl_rand_hex_32_or_longer_secret"
    );

    private JwtSecretValidator() {
    }

    /**
     * Enforced when spring profile {@code production} is active.
     */
    public static void requireProductionSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Production JWT secret is missing. Set JWT_SECRET in .env "
                            + "(labops.jwt.secret) to a random value of at least 32 bytes.");
        }
        String trimmed = secret.trim();
        int bytes = trimmed.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "Production JWT secret is too short (" + bytes + " bytes). "
                            + "Require at least " + MIN_SECRET_BYTES + " bytes.");
        }
        if (isPlaceholder(trimmed)) {
            throw new IllegalStateException(
                    "Production JWT secret looks like a placeholder. "
                            + "Set JWT_SECRET in .env to a unique random secret (do not use .env.example values).");
        }
    }

    static boolean isPlaceholder(String secret) {
        if (BANNED_EXACT.contains(secret)) {
            return true;
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        return lower.contains("change-me")
                || lower.contains("change_me")
                || lower.contains("replace_me")
                || lower.contains("replace-me")
                || lower.contains("your-secret")
                || lower.contains("placeholder");
    }
}
