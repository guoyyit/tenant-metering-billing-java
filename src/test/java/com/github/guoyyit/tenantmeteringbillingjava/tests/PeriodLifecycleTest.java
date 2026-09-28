package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.IngestStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.service.BillingException;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodLifecycleTest {

    @TempDir
    Path tempDir;

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private TestEnv env() throws IOException {
        TestEnv env = new TestEnv(Files.createTempDirectory(tempDir, "life"));
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                java.util.Arrays.asList((Long) null), List.of(new BigDecimal("1.00")));
        return env;
    }

    @Test
    void closeIsIdempotentAndBlocksWrites() throws IOException {
        TestEnv env = env();
        AuditEntry c1 = env.lifecycleService.closePeriod("t1", "2026-09", "month end");
        AuditEntry c2 = env.lifecycleService.closePeriod("t1", "2026-09", "again");
        assertEquals(c1.id(), c2.id());
        assertEquals(c1.timestampEpochMs(), c2.timestampEpochMs());

        BillingException ex = assertThrows(BillingException.class,
                () -> env.ingestService.ingest("t1", "api_calls",
                        utc("2026-09-15T00:00:00Z"), "k-closed"));
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void reopenAllowsCorrectionAndNewBillAndKeepsAudit() throws IOException {
        TestEnv env = env();
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-10T00:00:00Z"), "k-1");
        BillView first = env.billingService.settle("t1", "2026-09");
        assertEquals(new BigDecimal("1.00"), first.bill().totalAmount());

        // 重开 -> 补记 -> 重新结算
        AuditEntry reopen = env.lifecycleService.reopenPeriod("t1", "2026-09", "missing records");
        var added = env.ingestService.ingest("t1", "api_calls",
                utc("2026-09-11T00:00:00Z"), "k-2");
        assertEquals(IngestStatus.ACCEPTED, added.status());

        BillView second = env.billingService.settle("t1", "2026-09");
        assertNotEquals(first.bill().id(), second.bill().id());
        assertEquals(new BigDecimal("2.00"), second.bill().totalAmount());
        assertEquals(2L, second.quantitiesAtSettlement().get("api_calls"));

        List<AuditEntry> audit = env.lifecycleService.audit("t1", "2026-09");
        List<String> actions = audit.stream().map(AuditEntry::action).toList();
        assertTrue(actions.contains("PERIOD_CLOSED"));
        assertTrue(actions.contains("PERIOD_REOPENED"));
        assertTrue(actions.stream().filter("BILL_SETTLED"::equals).count() >= 2);
        assertEquals("PERIOD_REOPENED", reopen.action());
    }

    @Test
    void reopenIsIdempotentWhenAlreadyOpen() throws IOException {
        TestEnv env = env();
        env.lifecycleService.closePeriod("t1", "2026-09", null);
        AuditEntry r1 = env.lifecycleService.reopenPeriod("t1", "2026-09", null);
        AuditEntry r2 = env.lifecycleService.reopenPeriod("t1", "2026-09", null);
        assertEquals(r1.id(), r2.id());

        // 从未关闭过重开应报错
        assertThrows(BillingException.class,
                () -> env.lifecycleService.reopenPeriod("t1", "2026-10", null));
    }
}
