package com.github.guoyyit.tenantmeteringbillingjava.model;

/**
 * 调用记录写入结果。
 */
public record IngestResult(
        IngestStatus status,
        String recordId,
        String assignedPeriodId,
        String reason
) {
}
