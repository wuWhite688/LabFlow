package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

class JwtServiceProductionSecretTest {

    private static Environment envWithProfiles(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources propertySources) {
                // keep defaults minimal
            }
        };
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        environment.getPropertySources().addFirst(
                new MapPropertySource("test", Map.of()));
        return environment;
    }

    @Test
    void productionProfileRejectsPlaceholderSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("labflow-prod-change-me-use-env-32b-min!!");

        assertThatThrownBy(() -> new JwtService(properties, envWithProfiles("production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void productionProfileRejectsMissingSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("");

        assertThatThrownBy(() -> new JwtService(properties, envWithProfiles("production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void productionProfileAcceptsStrongSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("labflow-local-verify-9f3c2a7b1e8d4c6a0b5f7e2d1c9a8b7e6d5c4b3a2f1e0d9c8b7a6");

        assertThatCode(() -> new JwtService(properties, envWithProfiles("production")))
                .doesNotThrowAnyException();
    }

    @Test
    void nonProductionStillRequiresMinimumLength() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");

        assertThatThrownBy(() -> new JwtService(properties, envWithProfiles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }
}
