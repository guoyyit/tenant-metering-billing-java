package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.model.PeriodType;

public record TenantConfiguredEvent(
        String tenantId,
        PeriodType periodType,
        long lateWindowMinutes,
        LateArrivalPolicy lateArrivalPolicy,
        long currentRuleVersion
) {
}
