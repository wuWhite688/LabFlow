package com.arthur.labops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Logging in to a disabled account must be indistinguishable from a wrong password.
 *
 * <p>AuthService deliberately has no enabled check of its own: Spring Security's
 * pre-authentication checks reject a disabled account before comparing the
 * password, so both paths surface AUTHENTICATION_FAILED. An attacker holding a
 * correct password for a suspended account learns nothing from the response.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisabledAccountLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformUserRepository userRepository;

    @AfterEach
    void reenableStudent() {
        userRepository.findByUsername("student").ifPresent(user -> {
            user.setEnabled(true);
            userRepository.saveAndFlush(user);
        });
        TestAuth.clearCache();
    }

    @Test
    void disabledAccountIsIndistinguishableFromWrongPassword() throws Exception {
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        student.setEnabled(false);
        userRepository.saveAndFlush(student);

        // Correct password, but the account is suspended.
        mockMvc.perform(login("student", "student123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        // Wrong password on the same suspended account: identical response code.
        mockMvc.perform(login("student", "definitely-not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String username, String password) throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "password", password)));
    }
}
