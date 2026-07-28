package com.arthur.labops;

import static com.arthur.labops.TestAuth.login;
import static com.arthur.labops.TestAuth.loginAccessHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.jwt.access-token-ttl=2s",
        "labops.jwt.refresh-token-ttl=1h"
})
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearAuthCache() {
        TestAuth.clearCache();
    }

    @Test
    void loginReturnsBearerTokensAndUserProfile() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "student",
                                "password", "student123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.username").value("student"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"));
    }

    @Test
    void rejectsBadPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "student",
                                "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void accessTokenAuthorizesMeAndRejectsStudentAdminApis() throws Exception {
        String studentToken = loginAccessHeader(mockMvc, objectMapper, "student", "student123");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        mockMvc.perform(get("/api/audit-logs").header(HttpHeaders.AUTHORIZATION, studentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users/technicians").header(HttpHeaders.AUTHORIZATION, studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredAccessTokenIsRejectedUntilRefresh() throws Exception {
        Map<String, Object> tokens = login(mockMvc, objectMapper, "teacher", "teacher123");
        String access = "Bearer " + tokens.get("accessToken");
        String refresh = (String) tokens.get("refreshToken");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isOk());

        Thread.sleep(2500);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isUnauthorized());

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        Map<String, Object> body = objectMapper.readValue(
                refreshed.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        String newAccess = "Bearer " + body.get("accessToken");
        String newRefresh = (String) body.get("refreshToken");

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("teacher"));

        // Old refresh token is rotated/revoked
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(newRefresh).isNotEqualTo(refresh);
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        Map<String, Object> tokens = login(mockMvc, objectMapper, "admin", "admin123");
        String access = "Bearer " + tokens.get("accessToken");
        String refresh = (String) tokens.get("refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // Access token may still work until expiry (stateless) — refresh is the revocable path.
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isOk());
    }
}
