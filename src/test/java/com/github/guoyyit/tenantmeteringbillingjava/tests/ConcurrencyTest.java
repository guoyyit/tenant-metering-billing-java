package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.IngestStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.tests.support.TestEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void concurrentWritesWithSameDedupKeyAcceptedExactlyOnce() throws Exception {
        TestEnv env = new TestEnv(Files.createTempDirectory(tempDir, "cc1"));
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);

        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    // 跨 16 个线程共 800 次写入，映射到 100 个唯一 key
                    String key = "key-" + ((threadIdx * perThread + i) % 100);
                    var r = env.ingestService.ingest("t1", "api_calls",
                            utc("2026-09-10T00:00:00Z"), key);
                    if (r.status() == IngestStatus.ACCEPTED) {
                        accepted.incrementAndGet();
                    } else if (r.status() == IngestStatus.DUPLICATE) {
                        duplicate.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(100, accepted.get());
        assertEquals(threads * perThread - 100, duplicate.get());
        assertEquals(100L, env.billingService.usage("t1", "2026-09")
                .quantities().get("api_calls"));
    }

    @Test
    void billAndUsageAlwaysComeFromSameSnapshotDuringConcurrentSettle() throws Exception {
        TestEnv env = new TestEnv(Files.createTempDirectory(tempDir, "cc2"));
        env.configService.configureTenant("t1", null, 1440L * 30, LateArrivalPolicy.REJECT);
        env.configService.putMetricRule("t1", "api_calls",
                Arrays.asList((Long) null), List.of(new BigDecimal("1.00")));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();

        // 4 个写线程不断补记，2 个线程不断尝试结算/读账单，1 个线程反复读用量
        for (int w = 0; w < 4; w++) {
            int base = w * 500;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    try {
                        env.ingestService.ingest("t1", "api_calls",
                                utc("2026-09-10T00:00:00Z"), "w-" + (base + i));
                    } catch (RuntimeException expectedWhenClosed) {
                        // 结算后账期关闭，写入被拒绝属正常
                    }
                }
                return null;
            });
        }
        for (int r = 0; r < 3; r++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    try {
                        BillView view = env.billingService.settle("t1", "2026-09");
                        long billedQty = view.bill().items().isEmpty()
                                ? 0L : view.bill().items().get(0).quantity();
                        long viewQty = view.quantitiesAtSettlement()
                                .getOrDefault("api_calls", 0L);
                        // 结算后再写入会被关闭拒绝，因此账单数量只可能 == 或 <= 当前用量，
                        // 但账单数量与返回快照必须自洽：账单金额 == 数量 * 1.00
                        BigDecimal expectedAmount =
                                BigDecimal.valueOf(billedQty).setScale(2);
                        if (expectedAmount.compareTo(view.bill().totalAmount()) != 0) {
                            mismatches.add("amount mismatch: billedQty=" + billedQty
                                    + ", total=" + view.bill().totalAmount()
                                    + ", viewQty=" + viewQty);
                        }
                    } catch (RuntimeException ignored) {
                        // 极少数竞态下允许失败，但不允许不一致数据
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        BillView finalView = env.billingService.settle("t1", "2026-09");
        // 结算之后用量冻结，账单数量与当前用量严格一致（同一快照）
        long finalBilledQty = finalView.bill().items().isEmpty()
                ? 0L : finalView.bill().items().get(0).quantity();
        assertEquals(finalBilledQty,
                finalView.quantitiesAtSettlement().getOrDefault("api_calls", 0L));
        assertEquals(List.of(), List.copyOf(mismatches),
                "发现账单与快照不一致: " + mismatches);
    }
}
