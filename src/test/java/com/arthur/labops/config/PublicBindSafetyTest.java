package com.arthur.labops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class PublicBindSafetyTest {

    private static final String DEMO_SECRET = "labflow-dev-only-change-me-32bytes-min!!";
    private static final String STRONG_SECRET =
            "labflow-local-verify-9f3c2a7b1e8d4c6a0b5f7e2d1c9a8b7e6d5c4b3a2f1e0d9c8b7a6";

    @Test
    void loopbackAllowsDemoUsersAndDemoSecret() {
        assertThatCode(() -> PublicBindSafety.assertSafeToBind("127.0.0.1", true, true, DEMO_SECRET))
                .doesNotThrowAnyException();
        assertThat(PublicBindSafety.isLoopback("127.0.0.1")).isTrue();
        assertThat(PublicBindSafety.isLoopback("localhost")).isTrue();
        assertThat(PublicBindSafety.isLoopback("::1")).isTrue();
    }

    @Test
    void missingOrBlankAddressIsNotTreatedAsLoopback() {
        assertThat(PublicBindSafety.isLoopback(null)).isFalse();
        assertThat(PublicBindSafety.isLoopback("")).isFalse();
        assertThat(PublicBindSafety.isLoopback("   ")).isFalse();

        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind(null, true, false, STRONG_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind("", true, false, STRONG_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind("   ", false, false, DEMO_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void nonLoopbackWithDemoUsersIsRejected() {
        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind("0.0.0.0", true, false, STRONG_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo");
    }

    @Test
    void nonLoopbackWithPlaceholderSecretIsRejected() {
        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind("0.0.0.0", false, false, DEMO_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void nonLoopbackWithStrongSecretAndNoDemoIsAllowed() {
        assertThatCode(() -> PublicBindSafety.assertSafeToBind("0.0.0.0", false, false, STRONG_SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    void nonLoopbackWithDemoCallbackTokenIsRejected() {
        assertThatThrownBy(() -> PublicBindSafety.assertSafeToBind(
                        "0.0.0.0", false, false, STRONG_SECRET, "labflow-simulated-channel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void nonLoopbackWithStrongCallbackTokenIsAllowed() {
        assertThatCode(() -> PublicBindSafety.assertSafeToBind(
                        "0.0.0.0", false, false, STRONG_SECRET, "labflow-callback-" + "a".repeat(24)))
                .doesNotThrowAnyException();
    }

    @Test
    void packagedLocalPropertiesStillBindLoopback() throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(in);
        }
        assertThat(properties.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void testPropertiesStillBindLoopback() throws Exception {
        Properties properties = new Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        assertThat(properties.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void productionPropertiesDisableDemoSeedByDefault() throws Exception {
        Properties properties = new Properties();
        try (var in = getClass().getResourceAsStream("/application-production.properties")) {
            properties.load(in);
        }
        assertThat(properties.getProperty("labops.demo-users.enabled")).isEqualTo("${LABOPS_DEMO_USERS:false}");
        assertThat(properties.getProperty("labops.demo-data.enabled")).isEqualTo("${LABOPS_DEMO_DATA:false}");
        assertThat(properties.getProperty("labops.jwt.secret")).isEqualTo("${JWT_SECRET}");
        assertThat(properties.getProperty("labops.payment.callback-token"))
                .isEqualTo("${PAYMENT_CALLBACK_TOKEN}");
    }
}
