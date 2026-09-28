package com.github.guoyyit.tenantmeteringbillingjava.event;

public record PeriodReopenedEvent(
        String tenantId,
        String periodId,
        long timestampEpochMs,
        String reason
) {
}
