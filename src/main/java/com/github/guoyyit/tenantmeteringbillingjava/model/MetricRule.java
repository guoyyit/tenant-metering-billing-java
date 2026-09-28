package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.util.List;

/**
 * 单个计费指标的规则。
 */
public record MetricRule(
        String metricName,
        long version,
        List<PriceTier> tiers
) {
}
