package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.util.List;
import java.util.Map;

/**
 * Billing configuration of one tenant.
 *
 * @param tenantId        tenant identifier
 * @param cycle           settlement cycle
 * @param latePolicy      policy for out-of-window records
 * @param lateWindowMillis records arriving more than this many milliseconds after the
 *                         target period closes are considered late; must be non-negative
 * @param nextRuleVersion next rule version to assign
 * @param rules           pricing rule history per metric name, sorted by version
 * @param updatedAt       last configuration change instant (epoch millis)
 */
public record TenantConfig(String tenantId,
                           SettlementCycle cycle,
                           LateArrivalPolicy latePolicy,
                           long lateWindowMillis,
                           long nextRuleVersion,
                           Map<String, List<RuleVersion>> rules,
                           long updatedAt) {

    public TenantConfig {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
    }

    public TenantConfig(String tenantId) {
        this(tenantId, SettlementCycle.MONTHLY, LateArrivalPolicy.NEAREST_WRITABLE_PERIOD,
                0L, 1L, Map.of(), 0L);
    }
}
