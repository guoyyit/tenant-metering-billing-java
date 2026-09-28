package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.BillSettledEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.Event;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodReopenedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RulePutEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.UsageAcceptedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.UsageRejectedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.model.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.model.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageRecord;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import com.github.guoyyit.tenantmeteringbillingjava.store.StateApplier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 默认事件应用器：把 WAL 事件确定性地落到内存状态，供在线提交与崩溃重放共用。
 * 不做任何 IO；同一事件序列必然得到同一状态（结果可复现）。
 */
@Component
public class DefaultStateApplier implements StateApplier {

    @Override
    public void apply(BillingState state, Event event) {
        if (event.seq() <= state.lastSeq) {
            return;
        }
        switch (event.type()) {
            case TENANT_CONFIGURED -> applyTenantConfigured(state, event);
            case RULE_PUT -> applyRulePut(state, event);
            case USAGE_ACCEPTED -> applyUsageAccepted(state, event);
            case USAGE_REJECTED -> applyUsageRejected(state, event);
            case PERIOD_CLOSED -> applyPeriodClosed(state, event);
            case PERIOD_REOPENED -> applyPeriodReopened(state, event);
            case BILL_SETTLED -> applyBillSettled(state, event);
        }
        state.lastSeq = event.seq();
    }

    private void applyTenantConfigured(BillingState state, Event event) {
        TenantConfiguredEvent e = (TenantConfiguredEvent) event.payload();
        state.configs.put(e.tenantId(), new TenantConfig(
                e.tenantId(), e.periodType(), e.lateWindowMinutes(),
                e.lateArrivalPolicy(), e.currentRuleVersion()));
        state.rules.computeIfAbsent(e.tenantId(), k -> new LinkedHashMap<>());
        audit(state, event, e.tenantId(), null, "TENANT_CONFIGURED",
                "period=" + e.periodType() + ", lateWindowMinutes=" + e.lateWindowMinutes()
                        + ", policy=" + e.lateArrivalPolicy()
                        + ", ruleVersion=" + e.currentRuleVersion());
    }

    private void applyRulePut(BillingState state, Event event) {
        RulePutEvent e = (RulePutEvent) event.payload();
        state.rules.computeIfAbsent(e.tenantId(), k -> new LinkedHashMap<>())
                .put(e.metricName(), e.rule());
        TenantConfig existing = state.configs.get(e.tenantId());
        if (existing != null && e.rule().version() > existing.currentRuleVersion()) {
            state.configs.put(e.tenantId(), new TenantConfig(
                    existing.tenantId(), existing.periodType(),
                    existing.lateWindowMinutes(), existing.lateArrivalPolicy(),
                    e.rule().version()));
        }
        audit(state, event, e.tenantId(), null, "RULE_PUT",
                "metric=" + e.metricName() + ", version=" + e.rule().version());
    }

    private void applyUsageAccepted(BillingState state, Event event) {
        UsageAcceptedEvent e = (UsageAcceptedEvent) event.payload();
        UsageRecord r = e.record();
        String dk = BillingState.dedupKey(r.tenantId(), r.dedupKey());
        if (state.dedupIndex.containsKey(dk)) {
            return;
        }
        state.dedupIndex.put(dk, r);
        state.periodUsage.computeIfAbsent(
                BillingState.periodKey(r.tenantId(), r.periodId()),
                k -> new ArrayList<>()).add(r);
        audit(state, event, r.tenantId(), r.periodId(), "USAGE_ACCEPTED",
                "metric=" + r.metricName() + ", occurTime=" + r.occurTimeEpochMs()
                        + ", dedupKey=" + r.dedupKey());
    }

    private void applyUsageRejected(BillingState state, Event event) {
        UsageRejectedEvent e = (UsageRejectedEvent) event.payload();
        audit(state, event, e.tenantId(), null, "USAGE_REJECTED",
                "metric=" + e.metricName() + ", occurTime=" + e.occurTimeEpochMs()
                        + ", dedupKey=" + e.dedupKey() + ", reason=" + e.reason());
    }

    private void applyPeriodClosed(BillingState state, Event event) {
        PeriodClosedEvent e = (PeriodClosedEvent) event.payload();
        state.closedPeriods.add(BillingState.periodKey(e.tenantId(), e.periodId()));
        audit(state, event, e.tenantId(), e.periodId(), "PERIOD_CLOSED",
                "reason=" + e.reason());
    }

    private void applyPeriodReopened(BillingState state, Event event) {
        PeriodReopenedEvent e = (PeriodReopenedEvent) event.payload();
        String pk = BillingState.periodKey(e.tenantId(), e.periodId());
        state.closedPeriods.remove(pk);
        Bill existing = state.currentBills.remove(pk);
        if (existing != null && existing.status() == BillStatus.SETTLED) {
            state.voidedBills.add(new Bill(
                    existing.id(), existing.tenantId(), existing.periodId(),
                    existing.items(), existing.totalAmount(), existing.ruleVersion(),
                    existing.createdAtEpochMs(), BillStatus.VOIDED));
        }
        audit(state, event, e.tenantId(), e.periodId(), "PERIOD_REOPENED",
                "reason=" + e.reason()
                        + (existing != null ? ", voidedBill=" + existing.id() : ""));
    }

    private void applyBillSettled(BillingState state, Event event) {
        BillSettledEvent e = (BillSettledEvent) event.payload();
        state.currentBills.put(
                BillingState.periodKey(e.bill().tenantId(), e.bill().periodId()),
                e.bill());
        audit(state, event, e.bill().tenantId(), e.bill().periodId(), "BILL_SETTLED",
                "bill=" + e.bill().id() + ", total=" + e.bill().totalAmount()
                        + ", ruleVersion=" + e.bill().ruleVersion());
    }

    private void audit(BillingState state, Event event, String tenantId,
                       String periodId, String action, String detail) {
        state.lastAuditId++;
        state.auditLog.add(new AuditEntry(
                state.lastAuditId, event.timestampEpochMs(),
                tenantId, periodId, action, detail));
    }
}
