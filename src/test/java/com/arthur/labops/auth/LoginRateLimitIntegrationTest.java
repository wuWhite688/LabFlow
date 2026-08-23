package com.arthur.labops.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.login.max-failures-per-identity=3",
        "labops.login.max-failures-per-ip=5",
        "labops.login.window=1h"
})
@AutoConfigureMockMvc
class LoginRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void failuresBelowThresholdStayUnauthorizedThenSucceeds() throws Exception {
        String ip = "203.0.113.10";
        failedLogin(ip, "student", "wrong");
        failedLogin(ip, "student", "wrong");
        mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("student", "student123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("student"));
    }

    @Test
    void thirdFailureOnSameIdentityReturns429() throws Exception {
        String ip = "203.0.113.11";
        failedLogin(ip, "teacher", "wrong");
        failedLogin(ip, "teacher", "wrong");
        failedLogin(ip, "teacher", "wrong");
        mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("teacher", "wrong")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void rotatingUsernamesStillHitsIpCap() throws Exception {
        String ip = "203.0.113.12";
        failedLogin(ip, "no-such-a", "x");
        failedLogin(ip, "no-such-b", "x");
        failedLogin(ip, "no-such-c", "x");
        failedLogin(ip, "no-such-d", "x");
        failedLogin(ip, "no-such-e", "x");
        mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("no-such-f", "x")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void attackerCannotLockVictimOnAnotherIp() throws Exception {
        failedLogin("203.0.113.13", "admin", "wrong");
        failedLogin("203.0.113.13", "admin", "wrong");
        failedLogin("203.0.113.13", "admin", "wrong");
        mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr("198.51.100.20"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("admin", "admin123")))
                .andExpect(status().isOk());
    }

    @Test
    void clientsBehindTheSameBffDoNotShareTheIpBucket() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(bffClientIp("203.0.113.60"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("bff-user-" + i, "wrong")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .with(bffClientIp("203.0.113.61"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("another-bff-user", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oversizedCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("s".repeat(51), "student123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("student", "p".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void failedLogin(String ip, String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, password)))
                .andExpect(status().isUnauthorized());
    }

    private String body(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static RequestPostProcessor bffClientIp(String ip) {
        return request -> {
            request.setRemoteAddr("172.18.0.5");
            request.addHeader(ClientIpResolver.BFF_CLIENT_IP_HEADER, ip);
            return request;
        };
    }
}
