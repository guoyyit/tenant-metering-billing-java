package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "billing.storage.data-dir=target/test-data/api")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void cleanData() throws Exception {
        // 该测试使用固定数据目录，运行前清空以保证规则版本等断言稳定
        Path dir = Path.of("target/test-data/api");
        if (java.nio.file.Files.exists(dir)) {
            try (var paths = java.nio.file.Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(pth -> {
                            try {
                                java.nio.file.Files.deleteIfExists(pth);
                            } catch (Exception ignored) {
                                // Windows 等平台锁文件时忽略
                            }
                        });
            }
        }
    }

    @Test
    @Order(1)
    void endToEndFlow() throws Exception {
        long occur = System.currentTimeMillis() - 60_000L;

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"api-t1","periodType":"MONTHLY",
                                 "lateWindowMinutes":43200,"lateArrivalPolicy":"REJECT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("api-t1"));

        mockMvc.perform(put("/api/tenants/api-t1/metrics/api_calls/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tierUpperBounds":[1000,null],
                                 "unitPrices":[0.10,0.05]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "tenantId", "api-t1", "metricName", "api_calls",
                "occurTimeEpochMs", occur, "dedupKey", "api-k-1"));
        mockMvc.perform(post("/api/usage").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // 重复提交
        mockMvc.perform(post("/api/usage").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        String period = java.time.Instant.ofEpochMilli(occur)
                .atOffset(java.time.ZoneOffset.UTC)
                .toString().substring(0, 7);

        mockMvc.perform(get("/api/tenants/api-t1/periods/{p}/usage", period))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantities.api_calls").value(1));

        mockMvc.perform(post("/api/tenants/api-t1/periods/{p}/settle", period))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.totalAmount").value(0.10))
                .andExpect(jsonPath("$.quantitiesAtSettlement.api_calls").value(1));

        // 结算幂等
        mockMvc.perform(post("/api/tenants/api-t1/periods/{p}/settle", period))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bill.totalAmount").value(0.10));

        // 关闭幂等
        mockMvc.perform(post("/api/tenants/api-t1/periods/{p}/close", period)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PERIOD_CLOSED"));

        mockMvc.perform(get("/api/tenants/api-t1/periods/{p}/audit", period))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").exists());

        // 超期记录被拒绝
        String oldBody = MAPPER.writeValueAsString(java.util.Map.of(
                "tenantId", "api-t1", "metricName", "api_calls",
                "occurTimeEpochMs", occur - 40L * 24 * 3600_000L, "dedupKey", "api-old-1"));
        mockMvc.perform(post("/api/usage").contentType(MediaType.APPLICATION_JSON).content(oldBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @Order(2)
    void invalidTenantReturns404() throws Exception {
        mockMvc.perform(get("/api/tenants/nope/periods/2026-09/usage"))
                .andExpect(status().isNotFound());
    }
}
