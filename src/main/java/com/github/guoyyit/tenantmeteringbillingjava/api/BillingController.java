package com.github.guoyyit.tenantmeteringbillingjava.api;

import com.github.guoyyit.tenantmeteringbillingjava.model.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.model.BillView;
import com.github.guoyyit.tenantmeteringbillingjava.model.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.model.PeriodType;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageView;
import com.github.guoyyit.tenantmeteringbillingjava.service.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.service.ConfigService;
import com.github.guoyyit.tenantmeteringbillingjava.service.IngestService;
import com.github.guoyyit.tenantmeteringbillingjava.service.PeriodLifecycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计量计费 REST 接口。
 */
@RestController
@RequestMapping("/api")
public class BillingController {

    private final ConfigService configService;
    private final IngestService ingestService;
    private final BillingService billingService;
    private final PeriodLifecycleService lifecycleService;

    public BillingController(ConfigService configService,
                             IngestService ingestService,
                             BillingService billingService,
                             PeriodLifecycleService lifecycleService) {
        this.configService = configService;
        this.ingestService = ingestService;
        this.billingService = billingService;
        this.lifecycleService = lifecycleService;
    }

    public record TenantRequest(String tenantId, PeriodType periodType,
                                Long lateWindowMinutes,
                                LateArrivalPolicy lateArrivalPolicy) {
    }

    public record MetricRuleView(String metricName, long version,
                                 List<com.github.guoyyit.tenantmeteringbillingjava.model.PriceTier> tiers) {
    }

    public record RuleRequest(List<Long> tierUpperBounds, List<BigDecimal> unitPrices) {
    }

    public record UsageRequest(String tenantId, String metricName,
                               Long occurTimeEpochMs, String dedupKey) {
    }

    public record PeriodActionRequest(String reason) {
    }

    @PostMapping("/tenants")
    public TenantConfig configureTenant(@RequestBody TenantRequest request) {
        return configService.configureTenant(request.tenantId(), request.periodType(),
                request.lateWindowMinutes(), request.lateArrivalPolicy());
    }

    @PutMapping("/tenants/{tenantId}/metrics/{metricName}/rules")
    public MetricRuleView putRule(@PathVariable String tenantId,
                                  @PathVariable String metricName,
                                  @RequestBody RuleRequest request) {
        var rule = configService.putMetricRule(tenantId, metricName,
                request.tierUpperBounds(), request.unitPrices());
        return new MetricRuleView(rule.metricName(), rule.version(), rule.tiers());
    }

    @PostMapping("/usage")
    public IngestResult ingest(@RequestBody UsageRequest request) {
        long occurTime = request.occurTimeEpochMs() == null
                ? System.currentTimeMillis() : request.occurTimeEpochMs();
        return ingestService.ingest(request.tenantId(), request.metricName(),
                occurTime, request.dedupKey());
    }

    @GetMapping("/tenants/{tenantId}/periods/{periodId}/usage")
    public UsageView usage(@PathVariable String tenantId,
                           @PathVariable String periodId) {
        return billingService.usage(tenantId, periodId);
    }

    @PostMapping("/tenants/{tenantId}/periods/{periodId}/settle")
    public BillView settle(@PathVariable String tenantId,
                           @PathVariable String periodId) {
        return billingService.settle(tenantId, periodId);
    }

    @GetMapping("/tenants/{tenantId}/periods/{periodId}/bill")
    public BillView bill(@PathVariable String tenantId,
                         @PathVariable String periodId) {
        return billingService.getBill(tenantId, periodId);
    }

    @PostMapping("/tenants/{tenantId}/periods/{periodId}/close")
    public AuditEntry close(@PathVariable String tenantId,
                            @PathVariable String periodId,
                            @RequestBody(required = false) PeriodActionRequest request) {
        return lifecycleService.closePeriod(tenantId, periodId,
                request == null ? null : request.reason());
    }

    @PostMapping("/tenants/{tenantId}/periods/{periodId}/reopen")
    public AuditEntry reopen(@PathVariable String tenantId,
                             @PathVariable String periodId,
                             @RequestBody(required = false) PeriodActionRequest request) {
        return lifecycleService.reopenPeriod(tenantId, periodId,
                request == null ? null : request.reason());
    }

    @GetMapping("/tenants/{tenantId}/periods/{periodId}/audit")
    public List<AuditEntry> audit(@PathVariable String tenantId,
                                  @PathVariable String periodId) {
        return lifecycleService.audit(tenantId, periodId);
    }
}
