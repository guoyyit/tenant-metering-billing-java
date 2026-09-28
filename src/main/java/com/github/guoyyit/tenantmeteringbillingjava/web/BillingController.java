package com.github.guoyyit.tenantmeteringbillingjava.web;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.domain.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;
import com.github.guoyyit.tenantmeteringbillingjava.domain.TenantConfig;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST facade for the metering &amp; billing service.
 *
 * <p>Tenants not seen before are provisioned lazily on their first write using the
 * configured defaults, so {@code POST /usage} and configuration endpoints work without
 * a separate registration call.
 */
@RestController
@RequestMapping("/api")
public class BillingController {

    private final BillingService service;

    public BillingController(BillingService service) {
        this.service = service;
    }

    // ---- usage -------------------------------------------------------

    public record UsageRequest(String tenantId, String metric, Long occurredAt,
                               String dedupKey, Long quantity) {
    }

    @PostMapping("/usage")
    @ResponseStatus(HttpStatus.OK)
    public IngestResult ingest(@RequestBody UsageRequest request) {
        long occurredAt = request.occurredAt() != null ? request.occurredAt()
                : System.currentTimeMillis();
        long quantity = request.quantity() != null ? request.quantity() : 1L;
        return service.ingest(request.tenantId(), request.metric(), occurredAt,
                request.dedupKey(), quantity);
    }

    // ---- tenant configuration ---------------------------------------

    public record TenantConfigRequest(String cycle, String latePolicy, Long lateWindowMillis) {
    }

    @PostMapping("/tenants/{tenantId}/config")
    public TenantConfig configure(@PathVariable String tenantId,
                                  @RequestBody TenantConfigRequest request) {
        return service.configureTenant(tenantId,
                SettlementCycle.valueOf(request.cycle()),
                LateArrivalPolicy.valueOf(request.latePolicy()),
                request.lateWindowMillis());
    }

    @GetMapping("/tenants/{tenantId}/config")
    public TenantConfig getConfig(@PathVariable String tenantId) {
        return service.getConfig(tenantId);
    }

    // ---- pricing rules ----------------------------------------------

    public record TierRequest(Long lowerBoundInclusive, Long upperBoundExclusive,
                              String unitPrice) {
    }

    public record RuleRequest(String metric, Long effectiveAt, List<TierRequest> tiers) {
    }

    @PostMapping("/tenants/{tenantId}/rules")
    public RuleVersionView configureRule(@PathVariable String tenantId,
                                         @RequestBody RuleRequest request) {
        List<PriceTier> tiers = request.tiers().stream()
                .map(t -> new PriceTier(t.lowerBoundInclusive(), t.upperBoundExclusive(),
                        new BigDecimal(t.unitPrice())))
                .toList();
        long version = service.configureRule(tenantId, request.metric(),
                request.effectiveAt(), tiers);
        return new RuleVersionView(version);
    }

    public record RuleVersionView(long version) {
    }

    // ---- period lifecycle -------------------------------------------

    @PostMapping("/tenants/{tenantId}/periods/{periodKey}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closePeriod(@PathVariable String tenantId,
                            @PathVariable String periodKey) {
        service.closePeriod(tenantId, periodKey);
    }

    @PostMapping("/tenants/{tenantId}/periods/{periodKey}/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reopenPeriod(@PathVariable String tenantId,
                             @PathVariable String periodKey) {
        service.reopenPeriod(tenantId, periodKey);
    }

    @PostMapping("/tenants/{tenantId}/periods/{periodKey}/settle")
    public Bill settle(@PathVariable String tenantId,
                       @PathVariable String periodKey) {
        return service.settle(tenantId, periodKey);
    }

    @GetMapping("/tenants/{tenantId}/periods/{periodKey}")
    public PeriodView getPeriod(@PathVariable String tenantId,
                                @PathVariable String periodKey) {
        return service.getPeriod(tenantId, periodKey);
    }

    @GetMapping("/tenants/{tenantId}/periods")
    public List<PeriodView> listPeriods(@PathVariable String tenantId) {
        return service.listPeriods(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/bills/{periodKey}")
    public Bill getBill(@PathVariable String tenantId,
                        @PathVariable String periodKey) {
        return service.getBill(tenantId, periodKey);
    }

    // ---- audit -------------------------------------------------------

    @GetMapping("/tenants/{tenantId}/audit")
    public List<AuditEntry> audit(@PathVariable String tenantId) {
        return service.audit(tenantId);
    }
}
