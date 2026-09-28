package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.IngestStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.tests.support.TestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IngestTest {

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @TempDir
    Path tempDir;

    private TestEnv env(String name) throws java.io.IOException {
        return new TestEnv(java.nio.file.Files.createTempDirectory(tempDir, name));
    }

    @Test
    void duplicateDedupKeyCountsOnce() throws java.io.IOException {
        TestEnv env = env("dup");
        env.configService.configureTenant("t1", null, 1440L, LateArrivalPolicy.REJECT);
        long occur = utc("2026-09-27T10:00:00Z");

        var first = env.ingestService.ingest("t1", "api_calls", occur, "k-1");
        var second = env.ingestService.ingest("t1", "api_calls", occur, "k-1");
        var otherMetric = env.ingestService.ingest("t1", "storage", occur, "k-2");

        assertEquals(IngestStatus.ACCEPTED, first.status());
        assertEquals(IngestStatus.DUPLICATE, second.status());
        assertEquals(first.recordId(), second.recordId());
        assertEquals(IngestStatus.ACCEPTED, otherMetric.status());

        var usage = env.billingService.usage("t1", "2026-09");
        assertEquals(1L, usage.quantities().get("api_calls"));
        assertEquals(1L, usage.quantities().get("storage"));
    }

    @Test
    void rejectPolicyRejectsOutOfWindowRecord() throws java.io.IOException {
        TestEnv env = env("reject");
        // 当前日期 2026-09-28，窗口 1 天：9 月初的记录超期
        env.configService.configureTenant("t1", null, 1440L, LateArrivalPolicy.REJECT);
        var result = env.ingestService.ingest("t1", "api_calls",
                utc("2026-09-01T01:00:00Z"), "old-1");
        assertEquals(IngestStatus.REJECTED, result.status());
        assertNull(result.assignedPeriodId());
        assertNull(env.billingService.usage("t1", "2026-09").quantities().get("api_calls"));
        var audit = env.lifecycleService.audit("t1", null);
        assertEquals("USAGE_REJECTED", audit.get(audit.size() - 1).action());
    }

    @Test
    void currentPeriodPolicyAssignsCurrentPeriod() throws java.io.IOException {
        TestEnv env = env("current");
        env.configService.configureTenant("t1", null, 1L, LateArrivalPolicy.CURRENT_PERIOD);
        var result = env.ingestService.ingest("t1", "api_calls",
                utc("2026-08-20T00:00:00Z"), "old-1");
        assertEquals(IngestStatus.ACCEPTED, result.status());
        assertEquals("2026-09", result.assignedPeriodId());
        assertEquals(1L, env.billingService.usage("t1", "2026-09").quantities().get("api_calls"));
    }

    @Test
    void nearestWritablePolicyFindsOpenPreviousPeriod() throws java.io.IOException {
        TestEnv env = new TestDirEnv(java.nio.file.Files.createTempDirectory(tempDir, "nearest"));
        // 7 月已关闭，8 月开着：超期记录应归入 8 月而不是当前 9 月
        env.lifecycleService.closePeriod("t1", "2026-07", "test");
        var result = env.ingestService.ingest("t1", "api_calls",
                utc("2026-07-10T00:00:00Z"), "late-1");
        assertEquals(IngestStatus.ACCEPTED, result.status());
        assertEquals("2026-08", result.assignedPeriodId());
        assertNotNull(env.billingService.usage("t1", "2026-08").quantities().get("api_calls"));
    }

    @Test
    void withinWindowLateRecordKeepsNaturalPeriod() throws java.io.IOException {
        TestEnv env = env("within");
        env.configService.configureTenant("t1", null, 1440L, LateArrivalPolicy.REJECT);
        var result = env.ingestService.ingest("t1", "api_calls",
                utc("2026-09-27T12:00:00Z"), "late-ok");
        assertEquals(IngestStatus.ACCEPTED, result.status());
        assertEquals("2026-09", result.assignedPeriodId());
    }

    /** NEAREST_WRITABLE 用极小窗口让任意历史记录都超期。 */
    private static class TestDirEnv extends TestEnv {
        TestDirEnv(Path dir) {
            super(dir);
            configService.configureTenant("t1", null, 0L, LateArrivalPolicy.NEAREST_WRITABLE);
        }
    }
}
