package com.arthur.labops.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

class PaymentCallbackTokenGuardTest {

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
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of()));
        return environment;
    }

    @Test
    void productionProfileRejectsSimulatedChannelDefault() {
        PaymentProperties properties = new PaymentProperties();
        properties.setCallbackToken("labflow-simulated-channel");

        assertThatThrownBy(() -> new PaymentCallbackTokenGuard(properties, envWithProfiles("production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void productionProfileRejectsMissingToken() {
        PaymentProperties properties = new PaymentProperties();
        properties.setCallbackToken("");

        assertThatThrownBy(() -> new PaymentCallbackTokenGuard(properties, envWithProfiles("production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void productionProfileAcceptsStrongToken() {
        PaymentProperties properties = new PaymentProperties();
        properties.setCallbackToken("labflow-callback-" + "9f3c2a7b1e8d4c6a");

        assertThatCode(() -> new PaymentCallbackTokenGuard(properties, envWithProfiles("production")))
                .doesNotThrowAnyException();
    }

    @Test
    void nonProductionKeepsTheSimulatedChannelDefault() {
        PaymentProperties properties = new PaymentProperties();
        assertThatCode(() -> new PaymentCallbackTokenGuard(properties, envWithProfiles()))
                .doesNotThrowAnyException();
    }
}
