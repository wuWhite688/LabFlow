package com.arthur.labops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentCallbackTokenValidatorTest {

    @Test
    void acceptsStrongRandomToken() {
        String token = "v".repeat(24) + "-production-callback";
        assertThatCode(() -> PaymentCallbackTokenValidator.requireProductionToken(token))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsTooShortToken() {
        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken("only-8b!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void rejectsKnownPlaceholdersAndSimulatedChannelDefault() {
        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken(
                        "labflow-simulated-channel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken(
                        "REPLACE_ME_with_openssl_rand_hex_16_or_longer_token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> PaymentCallbackTokenValidator.requireProductionToken(
                        "labflow-callback-change-me-please!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void presentedTokenMatchesExpectedUtf8Bytes() {
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "labflow-callback-9f3c", "labflow-callback-9f3c")).isTrue();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "渠道口令-密钥", "渠道口令-密钥")).isTrue();
    }

    @Test
    void presentedTokenRejectsMismatchNullAndEmpty() {
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "labflow-callback-9f3c", "labflow-callback-9f3d")).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "labflow-callback-9f3c", "labflow-callback-9f3")).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "渠道口令-密钥", "渠道口令-密鑰")).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                "labflow-callback-9f3c", null)).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(
                null, "labflow-callback-9f3c")).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken(null, null)).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken("", null)).isFalse();
        assertThat(PaymentCallbackTokenValidator.matchesPresentedToken("", "")).isTrue();
    }
}
