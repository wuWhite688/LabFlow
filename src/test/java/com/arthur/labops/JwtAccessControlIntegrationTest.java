package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static com.arthur.labops.TestAuth.loginAccessHeader;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreDemoUsers() {
        userRepository.findByUsername("student").ifPresent(student -> {
            student.setEnabled(true);
            student.setRole(UserRole.STUDENT);
            userRepository.saveAndFlush(student);
        });
        userRepository.findByUsername("admin").ifPresent(admin -> {
            admin.setEnabled(true);
            admin.setRole(UserRole.ADMIN);
            userRepository.saveAndFlush(admin);
        });
        TestAuth.clearCache();
    }

    @Test
    void disabledUserAccessTokenIsRejectedOnEquipmentAndDashboard() throws Exception {
        String token = bearer(mockMvc, objectMapper, "student", "student123");
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        student.setEnabled(false);
        userRepository.saveAndFlush(student);

        mockMvc.perform(get("/api/equipment").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/stats").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletedUserAccessTokenIsRejected() throws Exception {
        PlatformUser temp = userRepository.saveAndFlush(new PlatformUser(
                "jwt-gone", passwordEncoder.encode("pass-gone-1"), "临时用户", UserRole.STUDENT));
        String token = loginAccessHeader(mockMvc, objectMapper, "jwt-gone", "pass-gone-1");
        jdbcTemplate.update("delete from refresh_tokens where user_id = ?", temp.getId());
        jdbcTemplate.update("delete from refresh_token_families where user_id = ?", temp.getId());
        userRepository.deleteById(temp.getId());
        userRepository.flush();

        mockMvc.perform(get("/api/equipment").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demotedAdminCannotReadAuditLogsWithOldToken() throws Exception {
        String token = bearer(mockMvc, objectMapper, "admin", "admin123");
        mockMvc.perform(get("/api/audit-logs").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        PlatformUser admin = userRepository.findByUsername("admin").orElseThrow();
        admin.setRole(UserRole.STUDENT);
        userRepository.saveAndFlush(admin);

        mockMvc.perform(get("/api/audit-logs").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    void enabledStudentStillReadsOwnProfile() throws Exception {
        String token = bearer(mockMvc, objectMapper, "student", "student123");
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }
}
