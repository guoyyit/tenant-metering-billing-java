package com.github.guoyyit.tenantmeteringbillingjava.event;

public record PeriodClosedEvent(
        String tenantId,
        String periodId,
        long timestampEpochMs,
        String reason
) {
}
