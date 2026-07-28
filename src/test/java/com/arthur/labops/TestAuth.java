package com.arthur.labops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

final class TestAuth {

    private static final ConcurrentHashMap<String, String> ACCESS_CACHE = new ConcurrentHashMap<>();

    private TestAuth() {
    }

    static void clearCache() {
        ACCESS_CACHE.clear();
    }

    static String bearer(MockMvc mockMvc, ObjectMapper objectMapper, String username, String password)
            throws Exception {
        String cached = ACCESS_CACHE.get(username);
        if (cached != null) {
            return cached;
        }
        String header = loginAccessHeader(mockMvc, objectMapper, username, password);
        ACCESS_CACHE.put(username, header);
        return header;
    }

    static Map<String, Object> login(MockMvc mockMvc, ObjectMapper objectMapper, String username, String password)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
    }

    static String loginAccessHeader(MockMvc mockMvc, ObjectMapper objectMapper, String username, String password)
            throws Exception {
        Map<String, Object> body = login(mockMvc, objectMapper, username, password);
        return "Bearer " + body.get("accessToken");
    }
}
