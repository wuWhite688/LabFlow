package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    @Test
    void usesBffAddressFromLoopbackOrPrivateProxy() {
        MockHttpServletRequest loopback = request("127.0.0.1", "203.0.113.20");
        MockHttpServletRequest dockerNetwork = request("172.18.0.4", "198.51.100.21");

        assertThat(ClientIpResolver.resolve(loopback)).isEqualTo("203.0.113.20");
        assertThat(ClientIpResolver.resolve(dockerNetwork)).isEqualTo("198.51.100.21");
    }

    @Test
    void ignoresSpoofedOrMalformedBffAddressOutsideTrustedNetwork() {
        assertThat(ClientIpResolver.resolve(request("198.51.100.30", "203.0.113.99")))
                .isEqualTo("198.51.100.30");
        assertThat(ClientIpResolver.resolve(request("127.0.0.1", "not-an-ip")))
                .isEqualTo("127.0.0.1");
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader(ClientIpResolver.BFF_CLIENT_IP_HEADER, forwardedAddress);
        return request;
    }
}
