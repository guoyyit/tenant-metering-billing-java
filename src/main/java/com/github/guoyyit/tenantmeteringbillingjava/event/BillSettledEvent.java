package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.model.Bill;

/**
 * 账单结算完成（账单为不可变快照）。
 */
public record BillSettledEvent(Bill bill) {
}
