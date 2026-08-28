package com.arthur.labops.payment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labops.payment")
public class PaymentProperties {

    /**
     * How long an approved reservation may sit in AWAITING_PAYMENT. It holds the
     * calendar slot for this whole window, so it is deliberately much shorter than
     * the approval timeout.
     */
    private Duration window = Duration.ofMinutes(10);

    /** Shared secret the channel presents on the callback endpoint. */
    private String callbackToken = "labflow-simulated-channel";

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }
}
