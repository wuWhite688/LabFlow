package com.arthur.labops.payment;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail closed on the production profile even when bound to loopback.
 * {@link com.arthur.labops.config.PublicBindSafety} covers the other axis:
 * a forgotten {@code java -jar} on a LAN NIC with the default profile.
 */
@Component
public class PaymentCallbackTokenGuard {

    public PaymentCallbackTokenGuard(PaymentProperties properties, Environment environment) {
        if (environment.matchesProfiles("production")) {
            PaymentCallbackTokenValidator.requireProductionToken(properties.getCallbackToken());
        }
    }
}
