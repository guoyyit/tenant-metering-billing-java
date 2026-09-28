package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.model.MetricRule;

/**
 * 规则版本写入（只对未结算周期生效）。
 */
public record RulePutEvent(
        String tenantId,
        String metricName,
        MetricRule rule,
        long timestampEpochMs
) {
}
