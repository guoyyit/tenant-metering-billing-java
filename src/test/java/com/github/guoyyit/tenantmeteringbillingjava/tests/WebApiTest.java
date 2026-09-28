package com.github.guoyyit.tenantmeteringbillingjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP flow: configure a rule, ingest a usage record, close &amp; settle, and
 * verify the generated bill and error mapping. Uses a test-specific data directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    static {
        // Unique data directory per run so tests never observe stale committed state.
        System.setProperty("metering.data-dir",
                "target/test-webapi-data-" + System.nanoTime());
        System.setProperty("metering.default-cycle", "MONTHLY");
        System.setProperty("metering.default-late-policy", "REJECT");
        System.setProperty("metering.default-late-window-millis", "0");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void configureIngestCloseSettleOverHttp() throws Exception {
        String tenant = "web-tenant";
        String month = com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator
                .currentPeriodKey(com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle.MONTHLY);

        // Tiered rule: first 10 at 1.00, the rest at 0.50.
        String rule = """
                {"metric":"api","tiers":[
                  {"lowerBoundInclusive":0,"upperBoundExclusive":10,"unitPrice":"1.00"},
                  {"lowerBoundInclusive":10,"upperBoundExclusive":null,"unitPrice":"0.50"}
                ]}""";
        mvc.perform(post("/api/tenants/{t}/rules", tenant)
                        .contentType("application/json").content(rule))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 15 units once, then the same dedupKey again (counted once -> still 15).
        String usage = """
                {"metric":"api","quantity":15,"dedupKey":"d1"}""";
        mvc.perform(post("/api/usage").contentType("application/json").content(usage
                        .replace("{", "{\"tenantId\":\"" + tenant + "\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/api/usage").contentType("application/json").content(usage
                        .replace("{", "{\"tenantId\":\"" + tenant + "\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // Snapshot reads: quantities and settlement state in one consistent view.
        mvc.perform(get("/api/tenants/{t}/periods/{p}", tenant, month))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantities.api").value(15))
                .andExpect(jsonPath("$.closed").value(false));

        mvc.perform(post("/api/tenants/{t}/periods/{p}/close", tenant, month))
                .andExpect(status().isNoContent());
        // Idempotent close.
        mvc.perform(post("/api/tenants/{t}/periods/{p}/close", tenant, month))
                .andExpect(status().isNoContent());

        // 10 @ 1.00 + 5 @ 0.50 = 12.50.
        mvc.perform(post("/api/tenants/{t}/periods/{p}/settle", tenant, month))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.totalAmount").value(12.50));

        mvc.perform(get("/api/tenants/{t}/bills/{p}", tenant, month))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].ruleVersion").value(1));

        // Unknown tenant -> 404 JSON.
        mvc.perform(get("/api/tenants/nope/config"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }
}
