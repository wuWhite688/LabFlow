package com.arthur.labops.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.arthur.labops.auth.JwtProperties;
import com.arthur.labops.auth.JwtSecretValidator;
import com.arthur.labops.payment.PaymentCallbackTokenValidator;
import com.arthur.labops.payment.PaymentProperties;

/**
 * Refuses to serve known demo credentials, a placeholder JWT secret, or the
 * simulated-channel callback token on a non-loopback bind. Local H2 on
 * 127.0.0.1 keeps the demo login experience.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicBindSafety implements ApplicationRunner {

    private final String serverAddress;
    private final boolean demoUsers;
    private final boolean demoData;
    private final JwtProperties jwtProperties;
    private final PaymentProperties paymentProperties;

    public PublicBindSafety(
            @Value("${server.address:#{null}}") String serverAddress,
            @Value("${labops.demo-users.enabled:false}") boolean demoUsers,
            @Value("${labops.demo-data.enabled:false}") boolean demoData,
            JwtProperties jwtProperties,
            PaymentProperties paymentProperties) {
        this.serverAddress = serverAddress;
        this.demoUsers = demoUsers;
        this.demoData = demoData;
        this.jwtProperties = jwtProperties;
        this.paymentProperties = paymentProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        assertSafeToBind(
                serverAddress,
                demoUsers,
                demoData,
                jwtProperties.getSecret(),
                paymentProperties.getCallbackToken());
    }

    static void assertSafeToBind(String serverAddress, boolean demoUsers, boolean demoData, String jwtSecret) {
        assertSafeToBind(serverAddress, demoUsers, demoData, jwtSecret, null);
    }

    static void assertSafeToBind(
            String serverAddress,
            boolean demoUsers,
            boolean demoData,
            String jwtSecret,
            String callbackToken) {
        if (isLoopback(serverAddress)) {
            return;
        }
        if (demoUsers || demoData) {
            throw new IllegalStateException(
                    "labops.demo-users/data cannot be enabled when server.address is not loopback. "
                            + "Bind 127.0.0.1 for local demo, or disable demo seed for a public bind.");
        }
        JwtSecretValidator.requireProductionSecret(jwtSecret);
        if (callbackToken != null) {
            PaymentCallbackTokenValidator.requireProductionToken(callbackToken);
        }
    }

    static boolean isLoopback(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return false;
        }
        String trimmed = serverAddress.trim();
        if ("localhost".equalsIgnoreCase(trimmed)) {
            return true;
        }
        try {
            return InetAddress.getByName(trimmed).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
