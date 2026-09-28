package com.github.guoyyit.tenantmeteringbillingjava.model;

/** 账单状态。 */
public enum BillStatus {
    /** 已结算（不可变） */
    SETTLED,
    /** 账期重开后原账单被作废，仅保留用于审计 */
    VOIDED
}
