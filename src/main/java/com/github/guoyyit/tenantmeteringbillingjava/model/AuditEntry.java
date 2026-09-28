package com.github.guoyyit.tenantmeteringbillingjava.model;

/**
 * 审计记录：账期关闭、重开、补记、规则变更、拒绝等所有关键操作均可追溯。
 */
public record AuditEntry(
        long id,
        long timestampEpochMs,
        String tenantId,
        String periodId,
        String action,
        String detail
) {
}
