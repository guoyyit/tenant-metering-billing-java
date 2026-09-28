package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.tests.support.TestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path tempDir;

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void restartPreservesRecordsBillAndDedupState() throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "recover");
        TestEnv env = new TestEnv(dir);
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                Arrays.asList((Long) null), List.of(new BigDecimal("2.00")));
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-10T00:00:00Z"), "k-1");
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-11T00:00:00Z"), "k-2");
        BillView before = env.billingService.settle("t1", "2026-09");
        assertEquals(new BigDecimal("4.00"), before.bill().totalAmount());

        // 模拟崩溃重启
        TestEnv restarted = env.restart();
        assertEquals(2L, restarted.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));
        assertTrue(restarted.lifecycleService.isClosed("t1", "2026-09"));

        BillView loaded = restarted.billingService.getBill("t1", "2026-09");
        assertEquals(before.bill().id(), loaded.bill().id());
        assertEquals(new BigDecimal("4.00"), loaded.bill().totalAmount());

        // 结算幂等：重启后再次结算返回同一账单
        BillView again = restarted.billingService.settle("t1", "2026-09");
        assertEquals(before.bill().id(), again.bill().id());

        // 去重状态恢复：旧 dedupKey 判重，不会重复计入
        var dup = restarted.ingestService.ingest("t1", "api_calls",
                utc("2026-09-10T00:00:00Z"), "k-1");
        assertEquals("DUPLICATE", dup.status().name());
        assertEquals(2L, restarted.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));
    }

    @Test
    void restartAfterReopenKeepsVoidedBillAndAllowsResettle() throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "reopen-recover");
        TestEnv env = new TestEnv(dir);
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                Arrays.asList((Long) null), List.of(new BigDecimal("1.00")));
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-10T00:00:00Z"), "k-1");
        BillView first = env.billingService.settle("t1", "2026-09");
        env.lifecycleService.reopenPeriod("t1", "2026-09", "adjustment");

        TestEnv restarted = env.restart();
        // 重开已落 WAL：账期可写、旧账单已作废
        assertEquals(false, restarted.lifecycleService.isClosed("t1", "2026-09"));
        var ex = expect404(() -> restarted.billingService.getBill("t1", "2026-09"));
        assertTrue(ex.getMessage().contains("未结算"));

        restarted.ingestService.ingest("t1", "api_calls",
                utc("2026-09-12T00:00:00Z"), "k-2");
        BillView second = restarted.billingService.settle("t1", "2026-09");
        assertNotEqualsId(first.bill().id(), second.bill().id());
        assertEquals(new BigDecimal("2.00"), second.bill().totalAmount());

        // 审计完整：含两次结算、关闭、重开
        var actions = restarted.lifecycleService.audit("t1", "2026-09")
                .stream().map(a -> a.action()).toList();
        assertTrue(actions.contains("PERIOD_REOPENED"));
        assertTrue(actions.stream().filter("BILL_SETTLED"::equals).count() >= 2);
    }

    @Test
    void snapshotThenRestartReplaysRemainingWal() throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "snapshot");
        // 每 3 个事件触发快照
        TestEnv env = new TestEnv(dir, 3);
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                Arrays.asList((Long) null), List.of(new BigDecimal("1.00")));
        for (int i = 0; i < 10; i++) {
            env.ingestService.ingest("t1", "api_calls",
                    utc("2026-09-10T00:00:00Z"), "k-" + i);
        }
        assertTrue(Files.exists(dir.resolve("snapshot.json")));

        TestEnv restarted = env.restart();
        assertEquals(10L, restarted.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));

        // 快照恢复后继续提交，序列号不冲突、状态延续
        restarted.ingestService.ingest("t1", "api_calls",
                utc("2026-09-10T00:00:00Z"), "k-10");
        assertEquals(11L, restarted.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));
    }

    @Test
    void tornWalLineAtTailIsDiscardedOnRecovery() throws IOException {
        Path dir = Files.createTempDirectory(tempDir, "torn");
        TestEnv env = new TestEnv(dir);
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                Arrays.asList((Long) null), List.of(new BigDecimal("1.00")));
        env.ingestService.ingest("t1", "api_calls", utc("2026-09-10T00:00:00Z"), "k-1");

        // 模拟崩溃时只写了半行
        Path wal = dir.resolve("wal.log");
        Files.write(wal, "{\"seq\":99,\"timestamp\":1,\"type\":\"BROKEN".getBytes(
                StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        TestEnv restarted = env.restart();
        // 完整事件恢复，半行被丢弃，且 wal 已被截断
        assertEquals(1L, restarted.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));
        String tail = Files.readString(wal);
        assertTrue(!tail.contains("BROKEN"), "半行应被截断");
    }

    private interface Action {
        void run();
    }

    private static com.github.guoyyit.tenantmeteringbillingjava.service.BillingException
            expect404(Action action) {
        try {
            action.run();
            throw new AssertionError("期望抛出 BillingException");
        } catch (com.github.guoyyit.tenantmeteringbillingjava.service.BillingException e) {
            return e;
        }
    }

    private static void assertNotEqualsId(String a, String b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
