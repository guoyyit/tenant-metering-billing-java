package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.service.PeriodService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodServiceTest {

    private final PeriodService periodService = new PeriodService();

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void periodIdUsesUtcMonth() {
        assertEquals("2026-09", periodService.periodIdOf(utc("2026-09-30T23:59:59Z")));
        assertEquals("2026-10", periodService.periodIdOf(utc("2026-10-01T00:00:00Z")));
        // 东八区本地已跨月，但 UTC 仍是 9 月
        assertEquals("2026-09", periodService.periodIdOf(utc("2026-09-30T16:30:00Z")));
    }

    @Test
    void boundariesAreHalfOpenUtc() {
        long start = periodService.periodStart("2026-09");
        long end = periodService.periodEnd("2026-09");
        assertEquals(utc("2026-09-01T00:00:00Z"), start);
        assertEquals(utc("2026-10-01T00:00:00Z"), end);
        assertTrue(periodService.inPeriod(start, "2026-09"));
        assertFalse(periodService.inPeriod(end, "2026-09"));
        assertTrue(periodService.inPeriod(end - 1, "2026-09"));
    }

    @Test
    void shiftAcrossYears() {
        assertEquals("2026-10", periodService.shift("2026-09", 1));
        assertEquals("2027-01", periodService.shift("2026-12", 1));
        assertEquals("2025-12", periodService.shift("2026-01", -1));
    }
}
