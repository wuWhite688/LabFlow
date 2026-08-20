package com.arthur.labops.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoUserInitializerConditionTest {

    @Test
    void missingPropertyDoesNotRegisterDemoUsers() {
        ConditionalOnProperty condition = DemoUserInitializer.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("labops.demo-users.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();

        new ApplicationContextRunner()
                .withUserConfiguration(DemoUserInitializer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DemoUserInitializer.class);
                });
    }

    @Test
    void localAndTestPropertiesStillEnableDemoUsers() throws Exception {
        Properties testProperties = new Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            testProperties.load(in);
        }
        assertThat(testProperties.getProperty("labops.demo-users.enabled")).isEqualTo("true");

        Properties localProperties = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            localProperties.load(in);
        }
        assertThat(localProperties.getProperty("labops.demo-users.enabled")).isEqualTo("true");
    }
}
