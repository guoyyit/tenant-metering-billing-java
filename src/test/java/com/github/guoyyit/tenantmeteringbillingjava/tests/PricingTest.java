package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.model.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.service.BillingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingTest {

    private static List<PriceTier> tiers(String p0, String p1, String p2) {
        return List.of(
                new PriceTier(0L, 1000L, new BigDecimal(p0)),
                new PriceTier(1000L, 10000L, new BigDecimal(p1)),
                new PriceTier(10000L, null, new BigDecimal(p2)));
    }

    @Test
    void tieredAmountSumsPerTier() {
        List<PriceTier> tiers = tiers("0.10", "0.05", "0.02");
        // 1000*0.10 + 9000*0.05 + 5000*0.02 = 100 + 450 + 100 = 650
        assertEquals(new BigDecimal("650.00"),
                BillingService.tieredAmount(tiers, 15000));
    }

    @Test
    void tieredAmountBelowFirstBoundary() {
        assertEquals(new BigDecimal("100.00"),
                BillingService.tieredAmount(tiers("0.10", "0.05", "0.02"), 1000));
    }

    @Test
    void tieredAmountZeroUsage() {
        assertEquals(new BigDecimal("0.00"),
                BillingService.tieredAmount(tiers("0.10", "0.05", "0.02"), 0));
    }
}
