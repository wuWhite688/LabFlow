package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * InnoDB proof for refresh-token family reuse. Default CI is H2; this class
 * starts MySQL 8.4 via Testcontainers when Docker is available and is required
 * to actually run on GitHub Actions (not skipped).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "labops.jwt.access-token-ttl=15m",
        "labops.jwt.refresh-token-ttl=1h",
        "labops.jwt.refresh-cookie-secure=true",
        "labops.demo-users.enabled=true",
        "labops.demo-data.enabled=false",
        "labops.reservation-lock.mode=local",
        "labops.reservation-expiry.mode=local",
        "labops.reservation-expiry.scan-interval=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect"
})
@AutoConfigureMockMvc
@Timeout(180)
class RefreshTokenMysqlConcurrencyTest {

    private static final String REFRESH_COOKIE_NAME = "labflow_refresh";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTokens() {
        jdbcTemplate.update("update refresh_token_families set current_token_id = null");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from refresh_token_families");
    }

    @Test
    void familyIdColumnIsNotNullWithoutDefaultZero() {
        String columnDefault = jdbcTemplate.queryForObject(
                """
                        select column_default
                          from information_schema.columns
                         where table_schema = database()
                           and table_name = 'refresh_tokens'
                           and column_name = 'family_id'
                        """,
                String.class);
        String nullable = jdbcTemplate.queryForObject(
                """
                        select is_nullable
                          from information_schema.columns
                         where table_schema = database()
                           and table_name = 'refresh_tokens'
                           and column_name = 'family_id'
                        """,
                String.class);
        assertThat(columnDefault).isNull();
        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    void concurrentRefreshOnMysqlLeavesZeroActiveAndKillsWinnerSuccessor() throws Exception {
        Cookie refresh = refreshCookie(login());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<MvcResult> refreshRequest = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult first = futures.get(0).get(30, TimeUnit.SECONDS);
            MvcResult second = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);

            MvcResult winner = first.getResponse().getStatus() == 200 ? first : second;
            MvcResult loser = winner == first ? second : first;
            assertThat(objectMapper.readTree(loser.getResponse().getContentAsByteArray())
                    .get("code").asText()).isEqualTo("INVALID_REFRESH_TOKEN");

            String winnerAccess = "Bearer " + responseBody(winner).get("accessToken");
            mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, winnerAccess))
                    .andExpect(status().isOk());

            assertThat(tokenActive(refresh)).isFalse();
            assertThat(tokenActive(refreshCookie(winner))).isFalse();
        }

        long activeTokens = refreshTokenRepository.findAll().stream()
                .filter(token -> token.isActive(Instant.now()))
                .count();
        assertThat(activeTokens).isEqualTo(0);
    }

    @Test
    void replayAfterRotationOnMysqlRevokesLivingSuccessor() throws Exception {
        Cookie tokenA = refreshCookie(login());
        MvcResult rotated = mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isOk())
                .andReturn();
        Cookie tokenB = refreshCookie(rotated);

        mockMvc.perform(post("/api/auth/refresh").cookie(tokenA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertThat(tokenActive(tokenB)).isFalse();
        String reason = jdbcTemplate.queryForObject(
                """
                        select f.revoke_reason
                          from refresh_token_families f
                          join refresh_tokens t on t.family_id = f.id
                         where t.token_hash = ?
                        """,
                String.class,
                hashToken(tokenB.getValue()));
        assertThat(reason).isEqualTo("REUSE");
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "teacher",
                                "password", "teacher123"))))
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

    private static String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
