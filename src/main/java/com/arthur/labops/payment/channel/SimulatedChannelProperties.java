package com.arthur.labops.payment.channel;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "labops.payment.channel")
public class SimulatedChannelProperties {

    public enum CallbackMode {
        /** Deliver on the calling thread. Deterministic; the default for tests. */
        IMMEDIATE,
        /** Hold callbacks until {@code deliverPending()} is called. Fully replayable. */
        MANUAL,
        /** Deliver after {@link #callbackDelay} via the shared task scheduler. */
        DELAYED
    }

    private CallbackMode callbackMode = CallbackMode.IMMEDIATE;

    private Duration callbackDelay = Duration.ofSeconds(2);

    /** Where the T+1 settlement files are written. */
    private String billDirectory = "target/channel-bills";

    public CallbackMode getCallbackMode() {
        return callbackMode;
    }

    public void setCallbackMode(CallbackMode callbackMode) {
        this.callbackMode = callbackMode;
    }

    public Duration getCallbackDelay() {
        return callbackDelay;
    }

    public void setCallbackDelay(Duration callbackDelay) {
        this.callbackDelay = callbackDelay;
    }

    public String getBillDirectory() {
        return billDirectory;
    }

    public void setBillDirectory(String billDirectory) {
        this.billDirectory = billDirectory;
    }
}
