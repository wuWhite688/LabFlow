package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtSecretValidatorTest {

    @Test
    void acceptsStrongRandomSecret() {
        String secret = "v".repeat(48) + "-production-grade-hmac-key";
        assertThatCode(() -> JwtSecretValidator.requireProductionSecret(secret))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsTooShortSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret("only-16-bytes!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void rejectsKnownPlaceholdersAndChangeMePatterns() {
        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret(
                        "labflow-prod-change-me-use-env-32b-min!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret(
                        "REPLACE_ME_with_openssl_rand_hex_32_or_longer_secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> JwtSecretValidator.requireProductionSecret(
                        "labflow-dev-only-change-me-32bytes-min!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }
}
