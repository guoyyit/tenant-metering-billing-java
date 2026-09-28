package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.tests.support.TestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingTest {

    @TempDir
    Path tempDir;

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private TestEnv env() throws IOException {
        return new TestEnv(Files.createTempDirectory(tempDir, "billing"));
    }

    private void seed(TestEnv env) {
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                java.util.Arrays.asList(1000L, 10000L, null),
                List.of(new BigDecimal("0.10"), new BigDecimal("0.05"), new BigDecimal("0.02")));
    }

    @Test
    void settleAppliesTieredPriceAndIsIdempotent() throws IOException {
        TestEnv env = env();
        seed(env);
        for (int i = 0; i < 1500; i++) {
            env.ingestService.ingest("t1", "api_calls",
                    utc("2026-09-10T00:00:00Z"), "k-" + i);
        }

        BillView first = env.billingService.settle("t1", "2026-09");
        // 1000*0.10 + 500*0.05 = 125
        assertEquals(new BigDecimal("125.00"), first.bill().totalAmount());
        assertEquals(1500L, first.quantitiesAtSettlement().get("api_calls"));
        String billId = first.bill().id();

        // 结算后账期自动关闭
        assertTrue(env.lifecycleService.isClosed("t1", "2026-09"));

        BillView again = env.billingService.settle("t1", "2026-09");
        assertEquals(billId, again.bill().id());
        assertEquals(new BigDecimal("125.00"), again.bill().totalAmount());
    }

    @Test
    void ruleChangeDoesNotAffectSettledBill() throws IOException {
        TestEnv env = env();
        seed(env);
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-10T00:00:00Z"), "k-1");
        BillView settled = env.billingService.settle("t1", "2026-09");
        assertEquals(new BigDecimal("0.10"),
                settled.bill().items().get(0).amount());
        long originalVersion = settled.bill().items().get(0).ruleVersion();

        // 规则变更：价格翻倍
        env.configService.putMetricRule("t1", "api_calls",
                java.util.Arrays.asList(1000L, null),
                List.of(new BigDecimal("0.20"), new BigDecimal("0.10")));

        BillView fetched = env.billingService.getBill("t1", "2026-09");
        assertEquals(new BigDecimal("0.10"),
                fetched.bill().items().get(0).amount());
        assertEquals(originalVersion, fetched.bill().items().get(0).ruleVersion());

        // 新规则对未结算的 10 月生效
        env.lifecycleService.reopenPeriod("t1", "2026-09", "补记到新周期验证规则");
        env.ingestService.ingest("t1", "api_calls", utc("2026-10-10T00:00:00Z"), "k-oct");
        BillView oct = env.billingService.settle("t1", "2026-10");
        assertEquals(new BigDecimal("0.20"), oct.bill().items().get(0).amount());
        assertTrue(oct.bill().items().get(0).ruleVersion() > originalVersion);
    }
}
