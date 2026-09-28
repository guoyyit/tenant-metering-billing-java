package com.github.guoyyit.tenantmeteringbillingjava.model;

/**
 * 租户计费配置。
 *
 * @param lateWindowMinutes 迟到时间窗（分钟）：记录发生时间早于 (接收时刻 - lateWindow) 视为超期
 */
public record TenantConfig(
        String tenantId,
        PeriodType periodType,
        long lateWindowMinutes,
        LateArrivalPolicy lateArrivalPolicy,
        long currentRuleVersion
) {
}
