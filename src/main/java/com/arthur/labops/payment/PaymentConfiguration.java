package com.arthur.labops.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.arthur.labops.payment.channel.SimulatedChannelProperties;

@Configuration
@EnableConfigurationProperties({PaymentProperties.class, SimulatedChannelProperties.class})
public class PaymentConfiguration {
}
