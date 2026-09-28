package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settlement racing with concurrent writers: settle requires a closed period, and the
 * single engine lock makes a bill correspond to exactly one committed prefix of usage.
 * A writer that lands after the close is redirected (never partially counted); the bill
 * amount always equals the billed quantity times the flat price.
 */
class SettlementConcurrencyTest {

    @Test
    void billAlwaysMatchesOneCommittedPrefix() throws Exception {
        BillingProperties props = new BillingProperties();
        props.setDataDir("target/test-settle-conc-" + System.nanoTime());
        props.setDefaultCycle("MONTHLY");
        props.setDefaultLatePolicy("NEAREST_WRITABLE_PERIOD");
        FileEventLog log = new FileEventLog(props, new ObjectMapper());
        BillingService svc = new BillingService(log, props);
        svc.init();

        String t = "sc";
        svc.configureRule(t, "m", null, List.of(
                new PriceTier(0L, null, new BigDecimal("1.00"))));
        String period = com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator
                .currentPeriodKey(com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle.MONTHLY);

        int writers = 6;
        int perWriter = 100;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong writtenBeforeClose = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int w = 0; w < writers; w++) {
            final int base = w * perWriter;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        long now = System.currentTimeMillis();
                        var r = svc.ingest(t, "m", now, "k-" + (base + i), 1);
                        if (r.accepted() && period.equals(r.periodKey())) {
                            writtenBeforeClose.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }

        // Closer/settler runs concurrently with the writers.
        CountDownLatch settled = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                start.await();
                // Give writers a head start, then close and settle while some may remain.
                Thread.sleep(5);
                svc.closePeriod(t, period);
                Bill bill = svc.settle(t, period);
                long billedQty = bill.lines().stream()
                        .filter(l -> l.metric().equals("m"))
                        .mapToLong(com.github.guoyyit.tenantmeteringbillingjava.domain.BillLine::quantity)
                        .sum();
                // The bill snapshot must be internally exact: amount == billedQty * 1.00.
                assertEquals(0, BigDecimal.valueOf(billedQty).compareTo(bill.totalAmount()),
                        "bill amount must equal its snapshot quantity");
                PeriodView view = svc.getPeriod(t, period);
                assertEquals(billedQty, view.quantities().get("m"),
                        "billed quantity must match the same stable snapshot");
                settled.countDown();
            } catch (Throwable e) {
                errors.add(e);
            }
        });

        start.countDown();
        assertTrue(settled.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), errors::toString);

        // Reopen and settle again after all writers drained: total now covers everything
        // that can be attributed to this period, and the new bill revision is exact too.
        svc.reopenPeriod(t, period);
        // Remaining writes that were redirected elsewhere are not pulled back; close again
        // after all threads finished so the period holds a stable final quantity.
        svc.closePeriod(t, period);
        Bill finalBill = svc.settle(t, period);
        assertEquals(2, finalBill.revision());
        long finalQty = finalBill.lines().stream()
                .filter(l -> l.metric().equals("m"))
                .mapToLong(com.github.guoyyit.tenantmeteringbillingjava.domain.BillLine::quantity)
                .sum();
        assertEquals(0, BigDecimal.valueOf(finalQty).compareTo(finalBill.totalAmount()));
        log.close();
    }
}
