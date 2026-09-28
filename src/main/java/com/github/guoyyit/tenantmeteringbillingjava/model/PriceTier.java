package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.math.BigDecimal;

/**
 * 阶梯单价区间：当用量落在 (upToExclusiveLower, upToInclusive] 时按 unitPrice 计费。
 * 最后一档 upperQuantityInclusive 为 null 表示无上限。
 */
public record PriceTier(
        long lowerQuantityExclusive,
        Long upperQuantityInclusive,
        BigDecimal unitPrice
) {
}
