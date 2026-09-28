package com.github.guoyyit.tenantmeteringbillingjava.model;

/**
 * 去重后已接受的调用记录。归属账期在写入时确定并固化。
 */
public record UsageRecord(
        String id,
        String tenantId,
        String metricName,
        long occurTimeEpochMs,
        long receivedTimeEpochMs,
        String dedupKey,
        String periodId
) {
}
