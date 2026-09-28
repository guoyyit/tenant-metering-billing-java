package com.github.guoyyit.tenantmeteringbillingjava.event;

public record UsageRejectedEvent(
        String tenantId,
        String metricName,
        String dedupKey,
        long occurTimeEpochMs,
        long receivedTimeEpochMs,
        String reason
) {
}
