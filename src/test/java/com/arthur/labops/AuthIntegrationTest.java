package com.arthur.labops;

import static com.arthur.labops.TestAuth.loginAccessHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.auth.RefreshTokenRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "labops.jwt.access-token-ttl=2s",
        "labops.jwt.refresh-token-ttl=1h",
        "labops.jwt.refresh-cookie-secure=true"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    private static final String REFRESH_COOKIE_NAME = "labflow_refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void resetAuthState() {
        TestAuth.clearCache();
        refreshTokenRepository.deleteAll();
    }

    @Test
    void loginReturnsAccessTokenAndSecureHttpOnlyRefreshCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "student",
                                "password", "student123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.username").value("student"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.tokenType").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        Map<String, Object> body = responseBody(result);
        assertThat(body.keySet()).containsExactlyInAnyOrder("accessToken", "expiresIn", "user");

        Cookie refreshCookie = refreshCookie(result);
        assertThat(refreshCookie.getValue()).isNotBlank();
        assertRefreshCookieAttributes(result, 3600);
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
        MvcResult loginResult = performLogin("teacher", "teacher123");
        Map<String, Object> tokens = responseBody(loginResult);
        String access = "Bearer " + tokens.get("accessToken");
        Cookie oldRefresh = refreshCookie(loginResult);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isOk());

        Thread.sleep(2500);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isUnauthorized());

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(oldRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        Map<String, Object> body = responseBody(refreshed);
        String newAccess = "Bearer " + body.get("accessToken");
        Cookie newRefresh = refreshCookie(refreshed);
        assertRefreshCookieAttributes(refreshed, 3600);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("teacher"));

        // Old refresh token is rotated/revoked
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(oldRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        MvcResult loginResult = performLogin("admin", "admin123");
        Map<String, Object> tokens = responseBody(loginResult);
        String access = "Bearer " + tokens.get("accessToken");
        Cookie refresh = refreshCookie(loginResult);

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                        .cookie(refresh))
                .andExpect(status().isNoContent())
                .andReturn();
        assertRefreshCookieAttributes(logoutResult, 0);
        assertThat(refreshCookie(logoutResult).getValue()).isEmpty();

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // Access token may still work until expiry (stateless) — refresh is the revocable path.
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, access))
                .andExpect(status().isOk());
    }

    @Test
    void refreshIgnoresJsonTokenAndReturnsNoContentWithoutCookie() throws Exception {
        MvcResult loginResult = performLogin("student", "student123");
        Cookie refresh = refreshCookie(loginResult);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", refresh.getValue()))))
                .andExpect(status().isNoContent());
    }

    @Test
    void concurrentRefreshWithSameCookieIssuesOnlyOneNewSession() throws Exception {
        Cookie refresh = refreshCookie(performLogin("teacher", "teacher123"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> refreshRequest = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent refresh start barrier timed out");
            }
            return mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(REFRESH_COOKIE_NAME, refresh.getValue())))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    executor.submit(refreshRequest),
                    executor.submit(refreshRequest));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        }

        long activeTokens = refreshTokenRepository.findAll().stream()
                .filter(token -> token.isActive(Instant.now()))
                .count();
        assertThat(activeTokens).isEqualTo(1);
    }

    private MvcResult performLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Map<String, Object> responseBody(MvcResult result) throws Exception {
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
    }

    private Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private void assertRefreshCookieAttributes(MvcResult result, int expectedMaxAge) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .isNotNull()
                .contains(REFRESH_COOKIE_NAME + "=")
                .contains("Path=/api/auth")
                .contains("Max-Age=" + expectedMaxAge)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax");
    }
}
