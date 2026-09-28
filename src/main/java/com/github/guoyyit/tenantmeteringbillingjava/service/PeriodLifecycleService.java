package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodReopenedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.model.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账期关闭/重开与审计查询。
 *
 * <p>关闭幂等：重复关闭不会产生新事件，返回首次关闭审计。
 * 重开幂等：已打开状态下重复重开不产生新事件。
 * 重开会把当前 SETTLED 账单移入作废列表（VOIDED 保留可查），允许补记后重新结算；
 * 关闭与重开均写审计事件。
 */
@Service
public class PeriodLifecycleService {

    private final Engine engine;
    private final ConfigService configService;

    public PeriodLifecycleService(Engine engine, ConfigService configService) {
        this.engine = engine;
        this.configService = configService;
    }

    public AuditEntry closePeriod(String tenantId, String periodId, String reason) {
        return engine.write(() -> {
            configService.requireConfig(tenantId);
            BillingState st = engine.state();
            String pk = BillingState.periodKey(tenantId, periodId);
            AuditEntry existing = findLast(st, tenantId, periodId, "PERIOD_CLOSED");
            if (st.closedPeriods.contains(pk)) {
                // 幂等：账期仍处于关闭状态，直接返回既有审计
                return existing;
            }
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.PERIOD_CLOSED,
                    () -> new PeriodClosedEvent(tenantId, periodId,
                            System.currentTimeMillis(),
                            reason == null ? "manual close" : reason));
            return st.auditLog.get(st.auditLog.size() - 1);
        });
    }

    public AuditEntry reopenPeriod(String tenantId, String periodId, String reason) {
        return engine.write(() -> {
            configService.requireConfig(tenantId);
            BillingState st = engine.state();
            String pk = BillingState.periodKey(tenantId, periodId);
            if (!st.closedPeriods.contains(pk)) {
                AuditEntry priorReopen = findLast(st, tenantId, periodId, "PERIOD_REOPENED");
                if (priorReopen != null) {
                    return priorReopen; // 幂等：已经是打开状态
                }
                throw new BillingException("账期未关闭，无需重开: " + periodId, 409);
            }
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.PERIOD_REOPENED,
                    () -> new PeriodReopenedEvent(tenantId, periodId,
                            System.currentTimeMillis(),
                            reason == null ? "manual reopen" : reason));
            return st.auditLog.get(st.auditLog.size() - 1);
        });
    }

    public boolean isClosed(String tenantId, String periodId) {
        return engine.read(() -> engine.state().closedPeriods.contains(
                BillingState.periodKey(tenantId, periodId)));
    }

    public List<AuditEntry> audit(String tenantId, String periodId) {
        return engine.read(() -> {
            configService.requireConfig(tenantId);
            return engine.state().auditLog.stream()
                    .filter(a -> tenantId.equals(a.tenantId())
                            && (periodId == null || periodId.equals(a.periodId())))
                    .toList();
        });
    }

    private AuditEntry findLast(BillingState st, String tenantId,
                                String periodId, String action) {
        AuditEntry found = null;
        for (AuditEntry a : st.auditLog) {
            if (tenantId.equals(a.tenantId())
                    && periodId.equals(a.periodId())
                    && action.equals(a.action())) {
                found = a;
            }
        }
        return found;
    }
}
