package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator;
import com.github.guoyyit.tenantmeteringbillingjava.domain.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.domain.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Late & duplicate record semantics:
 * <ul>
 *   <li>a record landing inside an open period and the late window is attributed there;</li>
 *   <li>once the target period is closed, REJECT / CURRENT_PERIOD / NEAREST_WRITABLE each
 *       produce their deterministic result;</li>
 *   <li>future-dated records are always rejected;</li>
 *   <li>replaying a rejected dedup key stays rejected and never gets counted.</li>
 * </ul>
 */
class LateArrivalTest {

    private BillingProperties props(Path dir, String policy, long windowMillis) {
        BillingProperties props = new BillingProperties();
        props.setDataDir(dir.toString());
        props.setDefaultCycle("DAILY");
        props.setDefaultLatePolicy(policy);
        props.setDefaultLateWindowMillis(windowMillis);
        return props;
    }

    private BillingService service(Path dir, String policy, long windowMillis) {
        BillingProperties p = props(dir, policy, windowMillis);
        FileEventLog log = new FileEventLog(p, new ObjectMapper());
        BillingService svc = new BillingService(log, p);
        svc.init();
        return svc;
    }

    private long dayStartUtc(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Test
    void rejectPolicyDropsRecordsForClosedPeriod() {
        BillingService svc = service(Path.of("target/test-late-reject-" + System.nanoTime()),
                "REJECT", 0L);
        String t = "t";
        long yesterday = dayStartUtc("2026-09-20");
        String yKey = "2026-09-20";
        svc.closePeriod(t, yKey);

        IngestResult r = svc.ingest(t, "m", yesterday + 1000, "k1", 1);
        assertFalse(r.accepted());
        assertNull(r.periodKey());

        List<PeriodView> periods = svc.listPeriods(t);
        // The closed period still exists (created by close) but got no quantity.
        PeriodView y = svc.getPeriod(t, yKey);
        assertTrue(y.closed());
        assertEquals(0L, y.quantities().getOrDefault("m", 0L));
        svc.shutdown();
    }

    @Test
    void currentPeriodPolicyRedirectsLateRecordToCurrentPeriod() {
        BillingService svc = service(Path.of("target/test-late-current-" + System.nanoTime()),
                "CURRENT_PERIOD", 0L);
        String t = "t";
        long now = System.currentTimeMillis();
        String current = PeriodCalculator.currentPeriodKey(SettlementCycle.DAILY);
        // A timestamp three days ago, whose period is closed.
        long old = now - 3L * 24 * 60 * 60 * 1000;
        String oldKey = PeriodCalculator.periodKey(SettlementCycle.DAILY, old);
        svc.closePeriod(t, oldKey);

        IngestResult r = svc.ingest(t, "m", old, "k1", 1);
        assertTrue(r.accepted());
        assertEquals(current, r.periodKey());
        assertEquals(1L, svc.getPeriod(t, current).quantities().get("m"));
        svc.shutdown();
    }

    @Test
    void nearestWritablePolicyFindsPreviousOpenPeriod() {
        BillingService svc = service(Path.of("target/test-late-near-" + System.nanoTime()),
                "NEAREST_WRITABLE_PERIOD", 0L);
        String t = "t";
        long now = System.currentTimeMillis();
        // Close a chain of old periods; the nearest still-open one must receive the record.
        long old4 = now - 4L * 24 * 60 * 60 * 1000;
        String k4 = PeriodCalculator.periodKey(SettlementCycle.DAILY, old4);
        String k3 = PeriodCalculator.nextPeriodKey(SettlementCycle.DAILY, k4);
        String k2 = PeriodCalculator.nextPeriodKey(SettlementCycle.DAILY, k3);
        svc.closePeriod(t, k4);
        svc.closePeriod(t, k3);
        // k2 is intentionally left open and writable.

        IngestResult r = svc.ingest(t, "m", old4, "k1", 2);
        assertTrue(r.accepted());
        assertEquals(k2, r.periodKey());
        assertEquals(2L, svc.getPeriod(t, k2).quantities().get("m"));
        svc.shutdown();
    }

    @Test
    void lateWindowGovernsElapsedButOpenPeriods() {
        // 7-day window: yesterday's period has elapsed naturally (not explicitly closed)
        // and is still inside the window -> attributed directly to yesterday.
        BillingService inside = service(Path.of("target/test-late-window-in-" + System.nanoTime()),
                "REJECT", 7L * 24 * 60 * 60 * 1000);
        String ti = "t";
        long yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        String yKey = PeriodCalculator.periodKey(SettlementCycle.DAILY, yesterday);
        IngestResult ok = inside.ingest(ti, "m", yesterday, "k1", 1);
        assertTrue(ok.accepted());
        assertEquals(yKey, ok.periodKey());
        inside.shutdown();

        // Window of 0: the same elapsed period is out of window and REJECT drops it.
        BillingService expired = service(Path.of("target/test-late-window-out-" + System.nanoTime()),
                "REJECT", 0L);
        IngestResult dropped = expired.ingest(ti, "m", yesterday, "k2", 1);
        assertFalse(dropped.accepted());
        assertNull(dropped.periodKey());
        expired.shutdown();
    }

    @Test
    void futureRecordsAreRejectedAndDedupStaysRejected() {
        BillingService svc = service(Path.of("target/test-late-future-" + System.nanoTime()),
                "NEAREST_WRITABLE_PERIOD", 0L);
        String t = "t";
        long future = System.currentTimeMillis() + 60_000L;
        IngestResult r1 = svc.ingest(t, "m", future, "k1", 1);
        assertFalse(r1.accepted());
        assertNull(r1.periodKey());

        IngestResult r2 = svc.ingest(t, "m", future, "k1", 1);
        assertFalse(r2.accepted());
        assertTrue(r2.duplicate());
        svc.shutdown();
    }
}
