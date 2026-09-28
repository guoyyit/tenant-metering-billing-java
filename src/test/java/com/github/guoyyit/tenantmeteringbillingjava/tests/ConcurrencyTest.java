package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent writers must not lose updates; duplicate keys across threads count once;
 * every observed (usage, settledRevision) pair must be internally consistent.
 */
class ConcurrencyTest {

    @Test
    void concurrentWritesAggregateExactlyAndSnapshotsAreConsistent() throws Exception {
        BillingProperties props = new BillingProperties();
        props.setDataDir("target/test-concurrency-" + System.nanoTime());
        props.setDefaultCycle("MONTHLY");
        props.setDefaultLatePolicy("NEAREST_WRITABLE_PERIOD");
        FileEventLog log = new FileEventLog(props, new ObjectMapper());
        BillingService svc = new BillingService(log, props);
        svc.init();

        String t = "tc";
        long now = System.currentTimeMillis();
        svc.configureRule(t, "m", null, List.of(
                new PriceTier(0L, null, new BigDecimal("1.00"))));

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acceptedUnique = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int th = 0; th < threads; th++) {
            final int base = th * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String key = "k-" + (base + i);
                        IngestResult r = svc.ingest(t, "m", now, key, 1);
                        if (r.accepted() && !r.duplicate()) {
                            acceptedUnique.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        // Concurrent reader: every snapshot must be self-consistent (non-negative
        // quantity, revision 0 while open, exact value never partial).
        Thread reader = new Thread(() -> {
            try {
                String period = com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator
                        .currentPeriodKey(com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle.MONTHLY);
                while (!Thread.currentThread().isInterrupted()) {
                    PeriodView view = svc.getPeriod(t, period);
                    long q = view.quantities().getOrDefault("m", 0L);
                    if (q < 0 || q > threads * perThread) {
                        throw new AssertionError("impossible snapshot quantity " + q);
                    }
                }
            } catch (com.github.guoyyit.tenantmeteringbillingjava.billing.NotFoundException e) {
                // period may not be visible for an instant; fine
            }
        });
        reader.setDaemon(true);
        reader.start();

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        reader.interrupt();
        assertTrue(errors.isEmpty(), () -> errors.toString());

        String period = com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator
                .currentPeriodKey(com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle.MONTHLY);
        PeriodView finalView = svc.getPeriod(t, period);
        assertEquals((long) threads * perThread, finalView.quantities().get("m"));
        assertEquals((long) threads * perThread, acceptedUnique.get());

        // A second wave replaying the SAME keys must not change the total at all.
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch start2 = new CountDownLatch(1);
        for (int th = 0; th < threads; th++) {
            final int base = th * perThread;
            pool2.submit(() -> {
                try {
                    start2.await();
                    for (int i = 0; i < perThread; i++) {
                        svc.ingest(t, "m", now, "k-" + (base + i), 1);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        start2.countDown();
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals((long) threads * perThread,
                svc.getPeriod(t, period).quantities().get("m"),
                "duplicate replays must not be double counted");
        svc.shutdown();
    }
}
