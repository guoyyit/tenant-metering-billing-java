package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.domain.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing rule changes are versioned and immutable once used:
 * <ul>
 *   <li>a settlement snapshots the rule version effective at the period end;</li>
 *   <li>adding a new version never changes an already generated bill;</li>
 *   <li>new versions apply to later unsettled periods;</li>
 *   <li>re-opening and re-settling an old period keeps the historically-effective rule.</li>
 * </ul>
 */
class RuleChangeTest {

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

    private static long utc(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Test
    void newPriceAppliesOnlyToLaterPeriodsAndOldBillsAreImmutable() {
        BillingService svc = service(Path.of("target/test-rule-change-" + System.nanoTime()));
        String t = "tr";
        String oldPeriod = "2026-07";
        String newPeriod = "2026-08";

        long v1Effective = utc("2026-01-01");
        long v1 = svc.configureRule(t, "api", v1Effective, List.of(
                new PriceTier(0L, null, new BigDecimal("1.00"))));
        assertEquals(1L, v1);

        // Settle July at the old price: 10 units -> 10.00.
        svc.ingest(t, "api", utc("2026-07-10"), "j1", 10);
        svc.closePeriod(t, oldPeriod);
        Bill julyBill = svc.settle(t, oldPeriod);
        assertEquals(0, new BigDecimal("10.00").compareTo(julyBill.totalAmount()));
        assertEquals(1L, julyBill.lines().get(0).ruleVersion());

        // New rule version effective 2026-08-01 (applies to August's period end).
        long v2 = svc.configureRule(t, "api", utc("2026-08-01"), List.of(
                new PriceTier(0L, null, new BigDecimal("2.00"))));
        assertEquals(2L, v2);

        // August settles at the new price: 10 units -> 20.00.
        svc.ingest(t, "api", utc("2026-08-10"), "a1", 10);
        svc.closePeriod(t, newPeriod);
        Bill augBill = svc.settle(t, newPeriod);
        assertEquals(0, new BigDecimal("20.00").compareTo(augBill.totalAmount()));
        assertEquals(2L, augBill.lines().get(0).ruleVersion());

        // The already-generated July bill is unchanged by the rule update.
        Bill julyStill = svc.getBill(t, oldPeriod);
        assertEquals(0, new BigDecimal("10.00").compareTo(julyStill.totalAmount()));
        assertEquals(1L, julyStill.lines().get(0).ruleVersion());

        // Re-open July, add usage, re-settle: still the historically effective v1 rule.
        svc.reopenPeriod(t, oldPeriod);
        svc.ingest(t, "api", utc("2026-07-20"), "j2", 5);
        svc.closePeriod(t, oldPeriod);
        Bill julyRev2 = svc.settle(t, oldPeriod);
        assertEquals(2, julyRev2.revision());
        // 15 units still @ 1.00 (v1), proving rule updates do not rewrite open history.
        assertEquals(0, new BigDecimal("15.00").compareTo(julyRev2.totalAmount()));
        assertEquals(1L, julyRev2.lines().get(0).ruleVersion());

        TenantConfig cfg = svc.getConfig(t);
        assertEquals(3L, cfg.nextRuleVersion(), "after v1 and v2 the next assignable is v3");
        assertEquals(2, cfg.rules().get("api").size());
        svc.shutdown();
    }

    @Test
    void invalidTierDefinitionsAreRejected() {
        BillingService svc = service(Path.of("target/test-rule-invalid-" + System.nanoTime()));
        // Gap between tiers.
        assertThrows(Exception.class, () -> svc.configureRule("t", "m", null, List.of(
                new PriceTier(0L, 10L, new BigDecimal("1")),
                new PriceTier(20L, null, new BigDecimal("2")))));
        // Last tier not open ended.
        assertThrows(Exception.class, () -> svc.configureRule("t", "m", null, List.of(
                new PriceTier(0L, 10L, new BigDecimal("1")),
                new PriceTier(10L, 20L, new BigDecimal("2")))));
        // Empty tier list.
        assertThrows(Exception.class, () -> svc.configureRule("t", "m", null, List.of()));

        // Failed validation must not provision/modify anything: tenant stays unknown.
        assertThrows(Exception.class, () -> svc.getConfig("t"));
        svc.shutdown();
    }
}
