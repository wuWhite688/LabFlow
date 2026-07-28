package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentWorkOrderClaimIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void onlyOneOfTwoConcurrentClaimsSucceeds() throws Exception {
        Long equipmentId = createEquipment("CLAIM-CONCURRENT-001");
        Long workOrderId = createWorkOrder(equipmentId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() ->
                    claim(workOrderId, "technician", "tech123", ready, startSignal));
            Future<Integer> second = executor.submit(() ->
                    claim(workOrderId, "technician2", "tech2123", ready, startSignal));
            ready.await();
            startSignal.countDown();

            List<Integer> statuses = List.of(first.get(), second.get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
    }

    private int claim(Long workOrderId, String username, String password,
                      CountDownLatch ready, CountDownLatch startSignal) throws Exception {
        ready.countDown();
        startSignal.await();
        return mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, username, password)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Long createEquipment(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "并发接单测试设备",
                                "category", "测试",
                                "location", "实验楼 C101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createWorkOrder(Long equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(mockMvc, objectMapper, "student", "student123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "并发接单故障",
                                "description", "用于验证悲观锁下仅一人接单成功",
                                "priority", "HIGH"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
