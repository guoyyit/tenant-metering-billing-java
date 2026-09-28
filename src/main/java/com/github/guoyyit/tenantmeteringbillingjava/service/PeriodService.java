package com.github.guoyyit.tenantmeteringbillingjava.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 账期计算：周期边界一律按 UTC，使用半开区间 [start, end)。
 * 当前仅支持 MONTHLY，账期 id 格式为 yyyy-MM（如 2026-09）。
 */
@Service
public class PeriodService {

    /** 返回给定时间戳所属的账期 id（如 2026-09）。 */
    public String periodIdOf(long epochMs) {
        return YearMonth.from(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC)).toString();
    }

    /** 账期起始（含），epoch ms。 */
    public long periodStart(String periodId) {
        return YearMonth.parse(periodId).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 账期结束（不含），epoch ms，即次月 1 日 00:00:00 UTC。 */
    public long periodEnd(String periodId) {
        return YearMonth.parse(periodId).plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 当前账期 id。 */
    public String currentPeriodId() {
        return periodIdOf(System.currentTimeMillis());
    }

    /** periodId 偏移 n 个周期。 */
    public String shift(String periodId, int n) {
        try {
            return YearMonth.parse(periodId).plusMonths(n).toString();
        } catch (DateTimeParseException e) {
            throw new BillingException("非法账期 id: " + periodId);
        }
    }

    /** 判断时间戳是否位于账期半开区间内。 */
    public boolean inPeriod(long epochMs, String periodId) {
        try {
            return epochMs >= periodStart(periodId) && epochMs < periodEnd(periodId);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 账期 id 的日期形式（每月 1 日，UTC）。 */
    public LocalDate firstDay(String periodId) {
        return YearMonth.parse(periodId).atDay(1);
    }
}
