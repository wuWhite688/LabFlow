package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PagingLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void oversizedPageSizeIsCapped() throws Exception {
        mockMvc.perform(get("/api/equipment")
                        .param("size", "5000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }
}
