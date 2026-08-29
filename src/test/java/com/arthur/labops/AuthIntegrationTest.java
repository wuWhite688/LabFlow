package com.arthur.labops;

import static com.arthur.labops.TestAuth.loginAccessHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.auth.AuthRefreshTx;
import com.arthur.labops.auth.RefreshOutcome;
import com.arthur.labops.auth.RefreshTokenRepository;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

    @Autowired
    private AuthRefreshTx authRefreshTx;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformUserRepository userRepository;

    @BeforeEach
    void resetAuthState() {
        TestAuth.clearCache();
        jdbcTemplate.update("update refresh_token_families set current_token_id = null");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from refresh_token_families");
        userRepository.findByUsername("teacher").ifPresent(teacher -> {
            if (!teacher.isEnabled()) {
                teacher.setEnabled(true);
                userRepository.saveAndFlush(teacher);
            }
        });
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
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        Map<String, Object> body = responseBody(refreshed);
        String newAccess = "Bearer " + body.get("accessToken");
        Cookie newRefresh = refreshCookie(refreshed);
        assertRefreshCookieAttributes(refreshed, 3600);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("teacher"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(newRefresh))
                .andExpect(status().isOk());
    }

    @Test
    void replayOfRotatedRefreshTokenRevokesSuccessorAfterCommit() throws Exception {
        ListAppender<ILoggingEvent> appender = attachReuseAppender();
        try {
            MvcResult loginResult = performLogin("teacher", "teacher123");
            Cookie tokenA = refreshCookie(loginResult);
            MvcResult rotated = mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isOk())
                    .andReturn();
            Cookie tokenB = refreshCookie(rotated);
            String accessB = "Bearer " + responseBody(rotated).get("accessToken");

            mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                    .andExpect(jsonPath("$.message").value("刷新令牌无效或已失效"));

            assertThat(familyReason(tokenB)).isEqualTo("REUSE");
            assertThat(tokenRevoked(tokenB)).isTrue();
            assertThat(tokenActive(tokenB)).isFalse();
            assertThat(reuseWarnings(appender)).isEqualTo(1);

            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, accessB))
                    .andExpect(status().isOk());
        } finally {
            detachReuseAppender(appender);
        }
    }

    @Test
    void replayOfAncestorAfterTwoRotationsRevokesCurrentSuccessor() throws Exception {
        Cookie tokenA = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie tokenB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isOk())
                .andReturn());
        Cookie tokenC = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenB))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(familyReason(tokenC)).isEqualTo("REUSE");
        assertThat(tokenActive(tokenA)).isFalse();
        assertThat(tokenActive(tokenB)).isFalse();
        assertThat(tokenActive(tokenC)).isFalse();
    }

    @Test
    void expiredNeverRotatedTokenDoesNotKillAnotherFamily() throws Exception {
        Cookie first = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie second = refreshCookie(performLogin("teacher", "teacher123"));
        expireToken(first);

        mockMvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(familyReason(first)).isNull();
        mockMvc.perform(post("/api/auth/refresh").cookie(second))
                .andExpect(status().isOk());
    }

    @Test
    void expiredRotatedAncestorStillRevokesLivingSuccessor() throws Exception {
        Cookie tokenA = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie tokenB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isOk())
                .andReturn());
        expireToken(tokenA);

        mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(familyReason(tokenB)).isEqualTo("REUSE");
        assertThat(tokenActive(tokenB)).isFalse();
    }

    @Test
    void rotatedChainWithNoLivingSuccessorIsNotReuse() throws Exception {
        ListAppender<ILoggingEvent> appender = attachReuseAppender();
        try {
            Cookie tokenA = refreshCookie(performLogin("teacher", "teacher123"));
            Cookie tokenB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isOk())
                    .andReturn());
            expireToken(tokenA);
            expireToken(tokenB);

            mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

            assertThat(familyReason(tokenA)).isNull();
            assertThat(reuseWarnings(appender)).isZero();

            RefreshOutcome outcome = authRefreshTx.terminateLiveFamilyInTx(tokenA.getValue());
            assertThat(outcome.kind()).isEqualTo(RefreshOutcome.Kind.INVALID);
            assertThat(familyReason(tokenA)).isNull();
        } finally {
            detachReuseAppender(appender);
        }
    }

    @Test
    void logoutCurrentThenRotatedAncestorIsNotReuse() throws Exception {
        ListAppender<ILoggingEvent> appender = attachReuseAppender();
        try {
            Cookie tokenA = refreshCookie(performLogin("admin", "admin123"));
            Cookie tokenB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isOk())
                    .andReturn());

            mockMvc.perform(post("/api/auth/logout").cookie(tokenB))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

            assertThat(familyReason(tokenA)).isEqualTo("LOGOUT");
            assertThat(tokenActive(tokenB)).isFalse();
            assertThat(reuseWarnings(appender)).isZero();
        } finally {
            detachReuseAppender(appender);
        }
    }

    @Test
    void logoutRotatedAncestorRevokesCurrentAndIsNotReuse() throws Exception {
        ListAppender<ILoggingEvent> appender = attachReuseAppender();
        try {
            Cookie tokenA = refreshCookie(performLogin("admin", "admin123"));
            Cookie tokenB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isOk())
                    .andReturn());

            mockMvc.perform(post("/api/auth/logout").cookie(tokenA))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

            assertThat(familyReason(tokenB)).isEqualTo("LOGOUT");
            assertThat(tokenActive(tokenB)).isFalse();
            assertThat(reuseWarnings(appender)).isZero();
        } finally {
            detachReuseAppender(appender);
        }
    }

    @Test
    void logoutRevokesOnlyThePresentedFamily() throws Exception {
        Cookie familyOne = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie familyTwo = refreshCookie(performLogin("teacher", "teacher123"));

        mockMvc.perform(post("/api/auth/logout").cookie(familyOne))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").cookie(familyTwo))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(familyOne))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reuseInOneFamilyLeavesAnotherFamilyActive() throws Exception {
        Cookie familyOneA = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie familyTwo = refreshCookie(performLogin("teacher", "teacher123"));
        Cookie familyOneB = refreshCookie(mockMvc.perform(post("/api/auth/refresh").cookie(familyOneA))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/auth/refresh").cookie(familyOneA))
                .andExpect(status().isUnauthorized());

        assertThat(familyReason(familyOneB)).isEqualTo("REUSE");
        mockMvc.perform(post("/api/auth/refresh").cookie(familyTwo))
                .andExpect(status().isOk());
    }

    @Test
    void disabledUserRefreshRevokesFamilyAsDisabledNotReuse() throws Exception {
        Cookie refresh = refreshCookie(performLogin("teacher", "teacher123"));
        PlatformUser teacher = userRepository.findByUsername("teacher").orElseThrow();
        teacher.setEnabled(false);
        userRepository.saveAndFlush(teacher);

        mockMvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));

        assertThat(familyReason(refresh)).isEqualTo("USER_DISABLED");
        assertThat(tokenActive(refresh)).isFalse();
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
    void refreshWithoutCookieReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutWithoutCookieReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void familyIdColumnIsNotNullWithoutDefaultZero() {
        assertThat(familyIdColumnDefault()).isNull();
        assertThat(familyIdColumnNullable()).isFalse();
    }

    @Test
    void unknownRefreshCookieIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE_NAME, "not-a-real-refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void concurrentRefreshWithSameCookieLeavesNoActiveSuccessor() throws Exception {
        Cookie refresh = refreshCookie(performLogin("teacher", "teacher123"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<MvcResult> refreshRequest = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent refresh start barrier timed out");
            }
            return mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(REFRESH_COOKIE_NAME, refresh.getValue())))
                    .andReturn();
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<MvcResult>> futures = List.of(
                    executor.submit(refreshRequest),
                    executor.submit(refreshRequest));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult first = futures.get(0).get(10, TimeUnit.SECONDS);
            MvcResult second = futures.get(1).get(10, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);

            MvcResult winner = first.getResponse().getStatus() == 200 ? first : second;
            MvcResult loser = winner == first ? second : first;
            assertThat(objectMapper.readTree(loser.getResponse().getContentAsByteArray())
                    .get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");

            String winnerAccess = "Bearer " + responseBody(winner).get("accessToken");
            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, winnerAccess))
                    .andExpect(status().isOk());

            Cookie winnerCookie = refreshCookie(winner);
            assertThat(tokenActive(refresh)).isFalse();
            assertThat(tokenActive(winnerCookie)).isFalse();
        }

        long activeTokens = refreshTokenRepository.findAll().stream()
                .filter(token -> token.isActive(Instant.now()))
                .count();
        assertThat(activeTokens).isEqualTo(0);
        assertThat(familyReason(refresh)).isEqualTo("REUSE");
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

    private void expireToken(Cookie cookie) {
        jdbcTemplate.update(
                "update refresh_tokens set expires_at = ? where token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                hashToken(cookie.getValue()));
    }

    private String familyReason(Cookie cookie) {
        return jdbcTemplate.queryForObject(
                """
                        select f.revoke_reason
                          from refresh_token_families f
                          join refresh_tokens t on t.family_id = f.id
                         where t.token_hash = ?
                        """,
                String.class,
                hashToken(cookie.getValue()));
    }

    private boolean tokenActive(Cookie cookie) {
        Integer active = jdbcTemplate.queryForObject(
                """
                        select count(*)
                          from refresh_tokens
                         where token_hash = ?
                           and revoked_at is null
                           and expires_at > current_timestamp
                        """,
                Integer.class,
                hashToken(cookie.getValue()));
        return active != null && active > 0;
    }

    private boolean tokenRevoked(Cookie cookie) {
        Timestamp revokedAt = jdbcTemplate.queryForObject(
                "select revoked_at from refresh_tokens where token_hash = ?",
                Timestamp.class,
                hashToken(cookie.getValue()));
        return revokedAt != null;
    }

    private String familyIdColumnDefault() {
        return jdbcTemplate.queryForObject(
                """
                        select column_default
                          from information_schema.columns
                         where lower(table_name) = 'refresh_tokens'
                           and lower(column_name) = 'family_id'
                        """,
                String.class);
    }

    private boolean familyIdColumnNullable() {
        String nullable = jdbcTemplate.queryForObject(
                """
                        select is_nullable
                          from information_schema.columns
                         where lower(table_name) = 'refresh_tokens'
                           and lower(column_name) = 'family_id'
                        """,
                String.class);
        return nullable != null && nullable.equalsIgnoreCase("YES");
    }

    private static String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ListAppender<ILoggingEvent> attachReuseAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        reuseLogger().addAppender(appender);
        return appender;
    }

    private static void detachReuseAppender(ListAppender<ILoggingEvent> appender) {
        reuseLogger().detachAppender(appender);
    }

    private static Logger reuseLogger() {
        return (Logger) LoggerFactory.getLogger("com.arthur.labops.auth.AuthRefreshTx");
    }

    private static long reuseWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("refresh token reused"))
                .count();
    }
}
