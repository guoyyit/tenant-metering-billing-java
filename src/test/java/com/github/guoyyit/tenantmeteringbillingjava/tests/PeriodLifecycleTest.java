package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Period close idempotency, reopen correction, bill revisions and audit trail. */
class PeriodLifecycleTest {

    private BillingService service(Path dir) {
        BillingProperties props = new BillingProperties();
        props.setDataDir(dir.toString());
        props.setDefaultCycle("MONTHLY");
        props.setDefaultLatePolicy("NEAREST_WRITABLE_PERIOD");
        FileEventLog log = new FileEventLog(props, new ObjectMapper());
        BillingService svc = new BillingService(log, props);
        svc.init();
        return svc;
    }

    @Test
    void closeIsIdempotentReopenAllowsCorrectionAndEveryChangeIsAudited() {
        BillingService svc = service(Path.of("target/test-lifecycle-" + System.nanoTime()));
        String t = "tenant-a";
        String p = "2026-08";

        long effectiveBeforePeriod = java.time.LocalDate.parse("2026-01-01")
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        svc.configureRule(t, "m", effectiveBeforePeriod, List.of(
                new PriceTier(0L, null, new BigDecimal("2.00"))));

        // First settlement: zero-usage period created by close, closed twice.
        svc.closePeriod(t, p);
        svc.closePeriod(t, p);
        Bill b1 = svc.settle(t, p);
        assertEquals(1, b1.revision());
        assertEquals(0, BigDecimal.ZERO.compareTo(b1.totalAmount()));

        // Closed period cannot be settled-open again: reopening is required to edit.
        assertThrows(Exception.class, () -> svc.reopenPeriod(t, "9999-01"));

        // Reopen, back-fill a record, settle again -> revision 2; revision 1 is retained.
        svc.reopenPeriod(t, p);
        PeriodView open = svc.getPeriod(t, p);
        assertTrue(!open.closed());
        long insidePeriod = java.time.LocalDate.parse("2026-08-15")
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        var accepted = svc.ingest(t, "m", insidePeriod, "corr-1", 3);
        assertTrue(accepted.accepted());
        assertEquals(p, accepted.periodKey());

        assertThrows(Exception.class, () -> svc.reopenPeriod(t, p),
                "reopening an open period must fail");
        svc.closePeriod(t, p);
        Bill b2 = svc.settle(t, p);
        assertEquals(2, b2.revision());
        assertEquals(0, new BigDecimal("6.00").compareTo(b2.totalAmount()));

        // The stored bill is the latest revision.
        assertEquals(2, svc.getBill(t, p).revision());

        // Audit: close appears twice (initial + re-close), the duplicate close is
        // silent, every effective change has an entry.
        List<String> actions = svc.audit(t).stream().map(AuditEntry::action).toList();
        assertEquals(2, actions.stream().filter(a -> a.equals("PERIOD_CLOSED")).count(),
                "effective closes only; the duplicate close emitted no event");
        assertTrue(actions.contains("PERIOD_REOPENED"));
        assertTrue(actions.contains("RECORD_ACCEPTED"));
        assertTrue(actions.contains("BILL_SETTLED"));
        svc.shutdown();
    }
}
