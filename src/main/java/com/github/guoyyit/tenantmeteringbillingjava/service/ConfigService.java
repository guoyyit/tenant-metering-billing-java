package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.RulePutEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.model.MetricRule;
import com.github.guoyyit.tenantmeteringbillingjava.model.PeriodType;
import com.github.guoyyit.tenantmeteringbillingjava.model.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 租户与计费规则配置。
 *
 * <p>每次规则写入产生新版本号（租户内全局递增）。结算时把当时的规则版本快照进账单行，
 * 此后再更新规则不影响已生成账单，因此“只对未结算周期生效”。
 */
@Service
public class ConfigService {

    public static final long DEFAULT_LATE_WINDOW_MINUTES = 60L * 24L;

    private final Engine engine;

    public ConfigService(Engine engine) {
        this.engine = engine;
    }

    public TenantConfig configureTenant(String tenantId, PeriodType periodType,
                                        Long lateWindowMinutes,
                                        LateArrivalPolicy policy) {
        requireText(tenantId, "tenantId");
        PeriodType pt = periodType == null ? PeriodType.MONTHLY : periodType;
        long window = lateWindowMinutes == null ? DEFAULT_LATE_WINDOW_MINUTES : lateWindowMinutes;
        LateArrivalPolicy p = policy == null ? LateArrivalPolicy.REJECT : policy;
        if (window < 0) {
            throw new BillingException("lateWindowMinutes 不能为负");
        }
        return engine.write(() -> {
            BillingState st = engine.state();
            long ruleVersion = st.configs.containsKey(tenantId)
                    ? st.configs.get(tenantId).currentRuleVersion() : 0L;
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.TENANT_CONFIGURED,
                    () -> new TenantConfiguredEvent(tenantId, pt, window, p, ruleVersion));
            return st.configs.get(tenantId);
        });
    }

    /**
     * 写入指标阶梯价格。tierUpperBounds 为各档上限（含），最后一档传 null 表示无上限；
     * 例如 [1000, 10000, null] 配三个单价。
     */
    public MetricRule putMetricRule(String tenantId, String metricName,
                                    List<Long> tierUpperBounds,
                                    List<BigDecimal> unitPrices) {
        requireText(metricName, "metricName");
        if (tierUpperBounds == null || unitPrices == null
                || tierUpperBounds.size() != unitPrices.size() || tierUpperBounds.isEmpty()) {
            throw new BillingException("阶梯上限与单价数量必须一致且非空");
        }
        List<PriceTier> tiers = buildTiers(tierUpperBounds, unitPrices);
        ensureTenant(tenantId);
        return engine.write(() -> {
            BillingState st = engine.state();
            long nextVersion = st.configs.get(tenantId).currentRuleVersion() + 1;
            MetricRule rule = new MetricRule(metricName, nextVersion, List.copyOf(tiers));
            long ts = System.currentTimeMillis();
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.RULE_PUT,
                    () -> new RulePutEvent(tenantId, metricName, rule, ts));
            return rule;
        });
    }

    public MetricRule currentRule(String tenantId, String metricName) {
        return engine.read(() -> {
            var byMetric = engine.state().rules.get(tenantId);
            return byMetric == null ? null : byMetric.get(metricName);
        });
    }

    public TenantConfig requireConfig(String tenantId) {
        TenantConfig config = engine.state().configs.get(tenantId);
        if (config == null) {
            throw new BillingException("租户未配置: " + tenantId, 404);
        }
        return config;
    }

    private void ensureTenant(String tenantId) {
        requireText(tenantId, "tenantId");
        engine.write(() -> {
            if (!engine.state().configs.containsKey(tenantId)) {
                configureTenant(tenantId, PeriodType.MONTHLY,
                        DEFAULT_LATE_WINDOW_MINUTES, LateArrivalPolicy.REJECT);
            }
            return null;
        });
    }

    private List<PriceTier> buildTiers(List<Long> uppers, List<BigDecimal> prices) {
        List<PriceTier> tiers = new ArrayList<>();
        long lower = 0L;
        for (int i = 0; i < uppers.size(); i++) {
            Long upper = uppers.get(i);
            BigDecimal price = prices.get(i);
            if (price == null || price.signum() < 0) {
                throw new BillingException("单价不能为空或为负");
            }
            if (upper != null) {
                if (upper <= lower) {
                    throw new BillingException("阶梯上限必须严格递增且为正");
                }
            } else if (i != uppers.size() - 1) {
                throw new BillingException("只有最后一档允许无上限");
            }
            tiers.add(new PriceTier(lower, upper, price));
            if (upper != null) {
                lower = upper;
            }
        }
        tiers.sort(Comparator.comparingLong(PriceTier::lowerQuantityExclusive));
        return tiers;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BillingException(name + " 不能为空");
        }
    }
}
