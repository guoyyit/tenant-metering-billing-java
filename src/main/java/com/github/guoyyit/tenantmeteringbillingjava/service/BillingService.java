package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.BillSettledEvent;
import com.github.guoyyit.tenantmeteringbillingjava.model.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillLineItem;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.MetricRule;
import com.github.guoyyit.tenantmeteringbillingjava.model.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageRecord;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageView;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 用量汇总与账单结算。
 *
 * <p>所有读取都在共享读锁内完成，因此同一次请求里的账单与累计用量来自同一状态快照，
 * 不会观察到“部分计入”。结算为写锁内的单条原子事件，与并发写入严格串行化。
 *
 * <p>阶梯计价采用“按档计价”：每个阶梯区间内的数量乘该档单价后求和。
 * 账单行固化当时规则版本与金额，之后规则变更不影响已生成账单。
 */
@Service
public class BillingService {

    private final Engine engine;
    private final ConfigService configService;

    public BillingService(Engine engine, ConfigService configService) {
        this.engine = engine;
        this.configService = configService;
    }

    public UsageView usage(String tenantId, String periodId) {
        return engine.read(() -> {
            configService.requireConfig(tenantId);
            BillingState st = engine.state();
            Map<String, Long> quantities = aggregate(st, tenantId, periodId);
            boolean closed = st.closedPeriods.contains(
                    BillingState.periodKey(tenantId, periodId));
            return new UsageView(tenantId, periodId, quantities, closed);
        });
    }

    /**
     * 幂等结算：账期已有 SETTLED 账单时直接返回原账单与结算时刻用量快照。
     */
    public BillView settle(String tenantId, String periodId) {
        return engine.write(() -> {
            BillingState st = engine.state();
            TenantConfig config = configService.requireConfig(tenantId);
            String pk = BillingState.periodKey(tenantId, periodId);

            Bill existing = st.currentBills.get(pk);
            if (existing != null && existing.status() == BillStatus.SETTLED) {
                return new BillView(existing, aggregate(st, tenantId, periodId));
            }

            Map<String, Long> quantities = aggregate(st, tenantId, periodId);
            Map<String, MetricRule> rules = st.rules.getOrDefault(tenantId, Map.of());

            List<BillLineItem> items = new java.util.ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            long maxVersion = 0L;
            for (Map.Entry<String, Long> q : new TreeMap<>(quantities).entrySet()) {
                MetricRule rule = rules.get(q.getKey());
                if (rule == null) {
                    throw new BillingException(
                            "指标 " + q.getKey() + " 缺少计费规则，无法结算", 422);
                }
                BigDecimal amount = tieredAmount(rule.tiers(), q.getValue());
                items.add(new BillLineItem(q.getKey(), q.getValue(),
                        rule.version(), amount));
                total = total.add(amount);
                maxVersion = Math.max(maxVersion, rule.version());
            }

            Bill bill = new Bill(
                    "bill-" + tenantId + "-" + periodId + "-" + UUID.randomUUID(),
                    tenantId, periodId, List.copyOf(items), total,
                    maxVersion, System.currentTimeMillis(), BillStatus.SETTLED);
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.BILL_SETTLED,
                    () -> new BillSettledEvent(bill));

            // 结算成功后关闭账期（幂等），冻结用量；需要修正可显式 reopen
            if (!st.closedPeriods.contains(pk)) {
                engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.PERIOD_CLOSED,
                        () -> new com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent(
                                tenantId, periodId, System.currentTimeMillis(),
                                "结算成功后自动关闭，规则版本基线=" + config.currentRuleVersion()));
            }
            return new BillView(bill, quantities);
        });
    }

    public BillView getBill(String tenantId, String periodId) {
        return engine.read(() -> {
            configService.requireConfig(tenantId);
            BillingState st = engine.state();
            Bill bill = st.currentBills.get(BillingState.periodKey(tenantId, periodId));
            if (bill == null) {
                throw new BillingException("账期未结算: " + periodId, 404);
            }
            Map<String, Long> quantities = aggregate(st, tenantId, periodId);
            return new BillView(bill, quantities);
        });
    }

    private Map<String, Long> aggregate(BillingState st, String tenantId, String periodId) {
        Map<String, Long> quantities = new LinkedHashMap<>();
        List<UsageRecord> records = st.periodUsage.get(
                BillingState.periodKey(tenantId, periodId));
        if (records != null) {
            for (UsageRecord r : records) {
                quantities.merge(r.metricName(), 1L, Long::sum);
            }
        }
        return quantities;
    }

    /**
     * 按档计价：数量在 (lower, upper] 区间部分乘对应单价。
     */
    public static BigDecimal tieredAmount(List<PriceTier> tiers, long quantity) {
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        long remaining = quantity;
        for (PriceTier tier : tiers) {
            if (remaining <= 0L) {
                break;
            }
            long span;
            if (tier.upperQuantityInclusive() == null) {
                span = remaining;
            } else {
                span = Math.min(remaining,
                        tier.upperQuantityInclusive() - tier.lowerQuantityExclusive());
            }
            amount = amount.add(tier.unitPrice().multiply(BigDecimal.valueOf(span)));
            remaining -= span;
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
