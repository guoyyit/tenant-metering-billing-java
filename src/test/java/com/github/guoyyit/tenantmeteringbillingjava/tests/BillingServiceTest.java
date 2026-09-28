package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.domain.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;
import com.github.guoyyit.tenantmeteringbillingjava.storage.EventLog;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end smoke test driving a brand new service through one monthly settlement. */
class BillingServiceTest {

    private BillingProperties props(Path dir) {
        BillingProperties props = new BillingProperties();
        props.setDataDir(dir.toString());
        props.setDefaultCycle("MONTHLY");
        props.setDefaultLatePolicy("REJECT");
        props.setDefaultLateWindowMillis(0L);
        return props;
    }

    private BillingService service(Path dir) {
        FileEventLog log = new FileEventLog(props(dir), new ObjectMapper());
        BillingService service = new BillingService(log, props(dir));
        service.init();
        return service;
    }

    @Test
    void ingestDedupCloseSettleFlow() {
        BillingService svc = service(Path.of("target/test-smoke-" + System.nanoTime()));
        String tenant = "t1";
        long now = System.currentTimeMillis();
        String month = PeriodCalculator.periodKey(SettlementCycle.MONTHLY, now);

        svc.configureRule(tenant, "api_calls", null, List.of(
                new PriceTier(0L, 100L, new BigDecimal("1.00")),
                new PriceTier(100L, null, new BigDecimal("0.50"))));

        IngestResult first = svc.ingest(tenant, "api_calls", now, "k1", 150);
        assertTrue(first.accepted());
        assertFalse(first.duplicate());
        assertEquals(month, first.periodKey());

        IngestResult dup = svc.ingest(tenant, "api_calls", now, "k1", 150);
        assertTrue(dup.accepted());
        assertTrue(dup.duplicate());

        IngestResult second = svc.ingest(tenant, "api_calls", now, "k2", 50);
        assertTrue(second.accepted());

        PeriodView view = svc.getPeriod(tenant, month);
        assertEquals(200L, view.quantities().get("api_calls"));

        svc.closePeriod(tenant, month);
        Bill bill = svc.settle(tenant, month);
        assertEquals(1, bill.revision());
        // 100 units @ 1.00 + 100 units @ 0.50 = 150
        assertEquals(0, new BigDecimal("150.00").compareTo(bill.totalAmount()));
        svc.shutdown();
    }
}
