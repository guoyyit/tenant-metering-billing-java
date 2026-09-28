package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.math.BigDecimal;

/**
 * 账单中单个指标的计费行，固化结算时的用量、规则版本与金额。
 */
public record BillLineItem(
        String metricName,
        long quantity,
        long ruleVersion,
        BigDecimal amount
) {
}
