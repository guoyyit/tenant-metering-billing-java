package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.util.Map;

/**
 * 某租户某账期的累计用量快照（指标名 -> 数量）。
 */
public record UsageView(
        String tenantId,
        String periodId,
        Map<String, Long> quantities,
        boolean closed
) {
}
