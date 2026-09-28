package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账单。SETTLED 账单不可变；账期重开时旧账单置为 VOIDED，重新结算生成新账单。
 */
public record Bill(
        String id,
        String tenantId,
        String periodId,
        List<BillLineItem> items,
        BigDecimal totalAmount,
        long ruleVersion,
        long createdAtEpochMs,
        BillStatus status
) {
}
