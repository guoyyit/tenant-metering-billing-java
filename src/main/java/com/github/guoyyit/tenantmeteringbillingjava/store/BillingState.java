package com.github.guoyyit.tenantmeteringbillingjava.store;

import com.github.guoyyit.tenantmeteringbillingjava.model.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.model.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.model.MetricRule;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计费服务的全部内存状态。所有变更必须先写 WAL 再调用 applier 落到此对象。
 */
public class BillingState {

    public long lastSeq;
    public long lastAuditId;

    public Map<String, TenantConfig> configs = new LinkedHashMap<>();
    /** tenantId -> metricName -> 当前生效规则（最新版本） */
    public Map<String, Map<String, MetricRule>> rules = new LinkedHashMap<>();
    /** tenantId|dedupKey -> 去重后记录 */
    public Map<String, UsageRecord> dedupIndex = new LinkedHashMap<>();
    /** tenantId|periodId -> 归属该账期的记录 */
    public Map<String, List<UsageRecord>> periodUsage = new LinkedHashMap<>();
    /** tenantId|periodId -> 当前有效（SETTLED）账单；重开后旧账单移入 voidedBills */
    public Map<String, Bill> currentBills = new LinkedHashMap<>();
    public List<Bill> voidedBills = new ArrayList<>();
    /** 已关闭账期集合 */
    public Set<String> closedPeriods = new LinkedHashSet<>();
    public List<AuditEntry> auditLog = new ArrayList<>();

    public static String dedupKey(String tenantId, String dedupKey) {
        return tenantId + "|" + dedupKey;
    }

    public static String periodKey(String tenantId, String periodId) {
        return tenantId + "|" + periodId;
    }
}
