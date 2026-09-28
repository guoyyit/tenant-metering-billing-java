package com.github.guoyyit.tenantmeteringbillingjava.model;

import java.util.Map;

/**
 * 账单与累计用量的一致性视图，二者来自同一状态快照。
 */
public record BillView(
        Bill bill,
        Map<String, Long> quantitiesAtSettlement
) {
}
