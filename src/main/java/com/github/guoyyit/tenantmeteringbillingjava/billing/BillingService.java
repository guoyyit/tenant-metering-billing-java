package com.github.guoyyit.tenantmeteringbillingjava.billing;

import com.github.guoyyit.tenantmeteringbillingjava.domain.AuditEntry;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.BillLine;
import com.github.guoyyit.tenantmeteringbillingjava.domain.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.domain.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodState;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.domain.RecordStatus;
import com.github.guoyyit.tenantmeteringbillingjava.domain.RuleVersion;
import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;
import com.github.guoyyit.tenantmeteringbillingjava.domain.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.domain.UsageRecord;
import com.github.guoyyit.tenantmeteringbillingjava.event.BillSettledEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.LogEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodReopenedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RecordIngestedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RuleConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantProvisionedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.storage.EventLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Local tenant metering &amp; billing engine.
 *
 * <p>All state lives in memory and is rebuilt by replaying the durable {@link EventLog}
 * on startup. A single {@link ReentrantReadWriteLock} serializes commits and gives
 * readers a stable snapshot: every read sees quantities and bills derived from the exact
 * same prefix of committed events. Writes append one event durably <em>before</em> the
 * in-memory state is advanced, so after a crash recovery either observes the whole commit
 * or none of it.
 */
@Service
public class BillingService {

    private final EventLog eventLog;
    private final BillingProperties properties;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** tenantId -> config (rules map value is mutable: metric -> versions). */
    private final Map<String, MutableConfig> configs = new TreeMap<>();
    /** tenantId -> periodKey -> state. */
    private final Map<String, Map<String, PeriodState>> periods = new TreeMap<>();
    /** tenantId -> periodKey -> latest bill. */
    private final Map<String, Map<String, Bill>> bills = new TreeMap<>();
    /** tenantId -> dedupKey -> first decision (accepted or rejected). */
    private final Map<String, Map<String, UsageRecord>> dedup = new TreeMap<>();
    /** tenantId -> audit entries (global order preserved by sequence). */
    private final Map<String, List<AuditEntry>> audit = new TreeMap<>();

    private static final class MutableConfig {
        SettlementCycle cycle;
        LateArrivalPolicy latePolicy;
        long lateWindowMillis;
        long nextRuleVersion;
        long updatedAt;
        final Map<String, List<RuleVersion>> rules = new TreeMap<>();
    }

    public BillingService(EventLog eventLog, BillingProperties properties) {
        this.eventLog = eventLog;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        lock.writeLock().lock();
        try {
            for (LogEvent event : eventLog.replay()) {
                apply(event);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        eventLog.close();
    }

    // ------------------------------------------------------------------
    // Usage ingestion
    // ------------------------------------------------------------------

    /**
     * Accepts a usage record.
     *
     * <p>Semantics:
     * <ul>
     *   <li>duplicate (same tenant + dedupKey): returns the first decision, counted once;</li>
     *   <li>records dated in the future are rejected (reproducible input validation);</li>
     *   <li>records belonging to an open writable period are attributed there directly;</li>
     *   <li>late records (target period closed or past its late window) follow the
     *       tenant policy: REJECT / CURRENT_PERIOD / NEAREST_WRITABLE_PERIOD.</li>
     * </ul>
     */
    public IngestResult ingest(String tenantId, String metric, long occurredAt,
                               String dedupKey, long quantity) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ValidationException("tenantId is required");
        }
        if (metric == null || metric.isBlank()) {
            throw new ValidationException("metric is required");
        }
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new ValidationException("dedupKey is required");
        }
        if (quantity <= 0) {
            throw new ValidationException("quantity must be positive");
        }
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            MutableConfig cfg = provisionIfAbsent(tenantId, now);

            UsageRecord existing = dedup.computeIfAbsent(tenantId, k -> new HashMap<>()).get(dedupKey);
            if (existing != null) {
                boolean wasAccepted = existing.status() == RecordStatus.ACCEPTED;
                return new IngestResult(wasAccepted, true,
                        existing.periodKey(),
                        wasAccepted
                                ? "duplicate dedupKey: already accepted, counted once"
                                : "duplicate dedupKey: previously rejected ("
                                        + existing.rejectReason() + ")");
            }

            String target = PeriodCalculator.periodKey(cfg.cycle, occurredAt);
            String periodKey;
            String rejectReason = null;

            if (occurredAt > now) {
                periodKey = null;
                rejectReason = "occurredAt is in the future";
            } else {
                String attribution = attribute(cfg, tenantId, target, now);
                if (attribution == null) {
                    periodKey = null;
                    rejectReason = "late record rejected by policy " + cfg.latePolicy
                            + " (target period " + target + ")";
                } else {
                    periodKey = attribution;
                }
            }

            RecordStatus status = periodKey != null ? RecordStatus.ACCEPTED : RecordStatus.REJECTED;
            RecordIngestedEvent event = new RecordIngestedEvent(0L, now, tenantId, metric,
                    occurredAt, dedupKey, quantity, now, status, periodKey, rejectReason);
            LogEvent committed = commit(event);
            UsageRecord saved = toRecord((RecordIngestedEvent) committed);
            dedup.get(tenantId).put(dedupKey, saved);
            return new IngestResult(status == RecordStatus.ACCEPTED, false, periodKey, rejectReason);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Decides which open writable period receives a record. Returns null when the record
     * must be rejected. The decision is a pure function of (config, period states, now),
     * hence reproducible for the same inputs.
     */
    private String attribute(MutableConfig cfg, String tenantId, String target, long now) {
        PeriodState targetState = periods.getOrDefault(tenantId, Map.of()).get(target);
        boolean targetClosed = targetState != null && targetState.closed();
        long targetEnd = PeriodCalculator.periodEndExclusive(cfg.cycle, target);
        boolean withinLateWindow = now < targetEnd + cfg.lateWindowMillis;

        if (!targetClosed && withinLateWindow) {
            return target;
        }
        return switch (cfg.latePolicy) {
            case REJECT -> null;
            case CURRENT_PERIOD -> {
                String current = PeriodCalculator.periodKey(cfg.cycle, now);
                PeriodState cur = periods.getOrDefault(tenantId, Map.of()).get(current);
                yield (cur == null || !cur.closed()) ? current : null;
            }
            case NEAREST_WRITABLE_PERIOD -> nearestWritable(cfg, tenantId, target, now);
        };
    }

    /** Most recent period at or before the target that is not closed; null if none. */
    private String nearestWritable(MutableConfig cfg, String tenantId, String target, long now) {
        String current = PeriodCalculator.periodKey(cfg.cycle, now);
        String candidate = target;
        // Walk forward toward the current period: a never-touched period is writable.
        while (true) {
            PeriodState state = periods.getOrDefault(tenantId, Map.of()).get(candidate);
            if (state == null || !state.closed()) {
                return candidate;
            }
            if (candidate.equals(current)) {
                return null;
            }
            candidate = PeriodCalculator.nextPeriodKey(cfg.cycle, candidate);
        }
    }

    // ------------------------------------------------------------------
    // Tenant & rule configuration
    // ------------------------------------------------------------------

    public TenantConfig configureTenant(String tenantId, SettlementCycle cycle,
                                        LateArrivalPolicy latePolicy, Long lateWindowMillis) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ValidationException("tenantId is required");
        }
        if (cycle == null) {
            throw new ValidationException("cycle is required");
        }
        if (latePolicy == null) {
            throw new ValidationException("latePolicy is required");
        }
        if (lateWindowMillis != null && lateWindowMillis < 0) {
            throw new ValidationException("lateWindowMillis must be non-negative");
        }
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            MutableConfig cfg = provisionIfAbsent(tenantId, now);
            TenantConfiguredEvent event = new TenantConfiguredEvent(0L, now, tenantId,
                    cycle.name(), latePolicy.name(),
                    lateWindowMillis != null ? lateWindowMillis : cfg.lateWindowMillis);
            commit(event);
            return snapshotConfig(tenantId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public TenantConfig getConfig(String tenantId) {
        lock.readLock().lock();
        try {
            requireTenant(tenantId);
            return snapshotConfig(tenantId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds a new pricing rule version for a metric. The rule applies only to periods
     * settled after the change: settlement always snapshots the version effective at the
     * period end, and already generated bills keep their own rule snapshot.
     *
     * @return assigned version number
     */
    public long configureRule(String tenantId, String metric, Long effectiveAt,
                              List<PriceTier> tiers) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ValidationException("tenantId is required");
        }
        if (metric == null || metric.isBlank()) {
            throw new ValidationException("metric is required");
        }
        List<PriceTier> validated = validateTiers(tiers);
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            MutableConfig cfg = provisionIfAbsent(tenantId, now);
            long version = cfg.nextRuleVersion;
            long effective = effectiveAt != null ? effectiveAt : now;
            if (effective > now) {
                throw new ValidationException("effectiveAt cannot be in the future");
            }
            RuleConfiguredEvent event = new RuleConfiguredEvent(0L, now, tenantId, metric,
                    version, effective, tiersJson(validated));
            commit(event);
            return version;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static List<PriceTier> validateTiers(List<PriceTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new ValidationException("at least one price tier is required");
        }
        List<PriceTier> sorted = tiers.stream()
                .sorted(Comparator.comparingLong(PriceTier::lowerBoundInclusive))
                .toList();
        long expected = 0L;
        for (PriceTier tier : sorted) {
            if (tier.lowerBoundInclusive() < 0) {
                throw new ValidationException("tier lower bounds must be non-negative");
            }
            if (tier.lowerBoundInclusive() != expected) {
                throw new ValidationException(
                        "tiers must be contiguous starting at 0; expected lower bound " + expected);
            }
            if (tier.upperBoundExclusive() != null
                    && tier.upperBoundExclusive() <= tier.lowerBoundInclusive()) {
                throw new ValidationException("tier upper bound must exceed lower bound");
            }
            if (tier.unitPrice() == null) {
                throw new ValidationException("tier unitPrice is required");
            }
            expected = tier.upperBoundExclusive() != null ? tier.upperBoundExclusive() : expected;
        }
        if (sorted.get(sorted.size() - 1).upperBoundExclusive() != null) {
            throw new ValidationException("the last tier must be open ended (upperBoundExclusive=null)");
        }
        return sorted;
    }

    // ------------------------------------------------------------------
    // Period lifecycle
    // ------------------------------------------------------------------

    /**
     * Closes a period. Idempotent: closing a non-existent period creates it in closed
     * state (so a never-written period can still be settled as zero usage); closing an
     * already closed period is a no-op without any new audit entry.
     */
    public void closePeriod(String tenantId, String periodKey) {
        validatePeriodRef(tenantId, periodKey);
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            provisionIfAbsent(tenantId, now);
            PeriodState state = periods.computeIfAbsent(tenantId, k -> new TreeMap<>())
                    .computeIfAbsent(periodKey, k -> new PeriodState(tenantId, periodKey));
            if (state.closed()) {
                return;
            }
            commit(new PeriodClosedEvent(0L, now, tenantId, periodKey));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reopens a closed period for correction / back-fill. Every reopen is audited. After
     * adding corrections the period can be settled again, producing a new bill revision;
     * prior bills remain queryable as history.
     */
    public void reopenPeriod(String tenantId, String periodKey) {
        validatePeriodRef(tenantId, periodKey);
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            provisionIfAbsent(tenantId, now);
            PeriodState state = periods.get(tenantId).get(periodKey);
            if (state == null) {
                throw new NotFoundException("period does not exist: " + periodKey);
            }
            if (!state.closed()) {
                throw new ValidationException("period is already open: " + periodKey);
            }
            commit(new PeriodReopenedEvent(0L, now, tenantId, periodKey));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Generates a bill for a period using the rule versions effective at the period end.
     * The bill and the usage snapshot are committed as one atomic event, so readers never
     * observe partial amounts. May be called repeatedly; each call creates a new revision.
     */
    public Bill settle(String tenantId, String periodKey) {
        validatePeriodRef(tenantId, periodKey);
        lock.writeLock().lock();
        try {
            requireTenant(tenantId);
            MutableConfig cfg = configs.get(tenantId);
            PeriodState state = periods.getOrDefault(tenantId, Map.of()).get(periodKey);
            if (state == null) {
                throw new NotFoundException("period does not exist: " + periodKey);
            }
            if (!state.closed()) {
                throw new ValidationException(
                        "period must be closed before settlement: " + periodKey);
            }
            long now = System.currentTimeMillis();
            long periodEnd = PeriodCalculator.periodEndExclusive(cfg.cycle, periodKey);
            int revision = state.settledRevision() + 1;
            // The period end is exclusive, so the rule "effective at period end" is the
            // one effective during the period's last millisecond (periodEnd - 1). This
            // keeps a rule going live exactly at the next period's start from leaking
            // into the previous period.
            long pricingAt = periodEnd - 1L;

            List<BillLine> lines = new ArrayList<>();
            for (Map.Entry<String, Long> entry : new TreeMap<>(state.quantities()).entrySet()) {
                String metric = entry.getKey();
                long quantity = entry.getValue();
                RuleVersion rule = ruleEffectiveAt(cfg, metric, pricingAt);
                if (rule == null) {
                    throw new ValidationException(
                            "no pricing rule configured for metric '" + metric
                                    + "' effective at period end " + periodKey);
                }
                lines.add(new BillLine(metric, quantity, rule.version(),
                        price(quantity, rule.tiers())));
            }
            BigDecimal total = lines.stream().map(BillLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Bill bill = new Bill(tenantId, periodKey, revision, lines, total, now);
            commit(new BillSettledEvent(0L, now, tenantId, periodKey, bill));
            return bill;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Bill getBill(String tenantId, String periodKey) {
        lock.readLock().lock();
        try {
            requireTenant(tenantId);
            Bill bill = bills.getOrDefault(tenantId, Map.of()).get(periodKey);
            if (bill == null) {
                throw new NotFoundException(
                        "no bill for tenant=" + tenantId + " period=" + periodKey);
            }
            return bill;
        } finally {
            lock.readLock().unlock();
        }
    }

    public PeriodView getPeriod(String tenantId, String periodKey) {
        lock.readLock().lock();
        try {
            requireTenant(tenantId);
            PeriodState state = periods.getOrDefault(tenantId, Map.of()).get(periodKey);
            if (state == null) {
                throw new NotFoundException("period does not exist: " + periodKey);
            }
            return toView(state);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PeriodView> listPeriods(String tenantId) {
        lock.readLock().lock();
        try {
            requireTenant(tenantId);
            return periods.getOrDefault(tenantId, Map.of()).values().stream()
                    .map(this::toView)
                    .sorted(Comparator.comparing(PeriodView::periodKey))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AuditEntry> audit(String tenantId) {
        lock.readLock().lock();
        try {
            requireTenant(tenantId);
            return List.copyOf(audit.getOrDefault(tenantId, List.of()));
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Durably applies then applies the event in-memory under the held write lock. */
    private LogEvent commit(LogEvent event) {
        LogEvent committed = eventLog.append(event);
        apply(committed);
        return committed;
    }

    private MutableConfig provisionIfAbsent(String tenantId, long now) {
        MutableConfig cfg = configs.get(tenantId);
        if (cfg != null) {
            return cfg;
        }
        cfg = new MutableConfig();
        cfg.cycle = SettlementCycle.valueOf(properties.getDefaultCycle());
        cfg.latePolicy = LateArrivalPolicy.valueOf(properties.getDefaultLatePolicy());
        cfg.lateWindowMillis = properties.getDefaultLateWindowMillis();
        cfg.nextRuleVersion = 1L;
        cfg.updatedAt = now;
        commit(new TenantProvisionedEvent(0L, now, tenantId, cfg.cycle.name(),
                cfg.latePolicy.name(), cfg.lateWindowMillis));
        return cfg;
    }

    private void requireTenant(String tenantId) {
        if (tenantId == null || !configs.containsKey(tenantId)) {
            throw new NotFoundException("tenant not found: " + tenantId);
        }
    }

    private static void validatePeriodRef(String tenantId, String periodKey) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ValidationException("tenantId is required");
        }
        if (periodKey == null || periodKey.isBlank()) {
            throw new ValidationException("periodKey is required");
        }
    }

    private static RuleVersion ruleEffectiveAt(MutableConfig cfg, String metric, long at) {
        List<RuleVersion> versions = cfg.rules.get(metric);
        if (versions == null) {
            return null;
        }
        RuleVersion found = null;
        for (RuleVersion v : versions) {
            if (v.effectiveAt() <= at) {
                found = v;
            }
        }
        return found;
    }

    /** Tiered pricing: each unit inside a tier is priced at that tier's unit price. */
    static BigDecimal price(long quantity, List<PriceTier> tiers) {
        BigDecimal amount = BigDecimal.ZERO;
        long remaining = quantity;
        for (PriceTier tier : tiers) {
            if (remaining <= 0) {
                break;
            }
            long span = tier.upperBoundExclusive() != null
                    ? tier.upperBoundExclusive() - tier.lowerBoundInclusive()
                    : Long.MAX_VALUE;
            long inTier = Math.min(remaining, span);
            amount = amount.add(tier.unitPrice().multiply(BigDecimal.valueOf(inTier)));
            remaining -= inTier;
        }
        return amount;
    }

    private PeriodView toView(PeriodState state) {
        return new PeriodView(state.tenantId(), state.periodKey(), state.closed(),
                state.settledRevision(), state.quantities());
    }

    private TenantConfig snapshotConfig(String tenantId) {
        MutableConfig cfg = configs.get(tenantId);
        Map<String, List<RuleVersion>> rules = cfg.rules.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
        return new TenantConfig(tenantId, cfg.cycle, cfg.latePolicy, cfg.lateWindowMillis,
                cfg.nextRuleVersion, rules, cfg.updatedAt);
    }

    private static UsageRecord toRecord(RecordIngestedEvent e) {
        return new UsageRecord(e.tenantId(), e.metric(), e.occurredAt(), e.dedupKey(),
                e.quantity(), e.receivedAt(), RecordStatus.valueOf(e.status()), e.periodKey(),
                e.rejectReason());
    }

    private static List<List<String>> tiersJson(List<PriceTier> tiers) {
        List<List<String>> json = new ArrayList<>();
        for (PriceTier tier : tiers) {
            List<String> row = new ArrayList<>();
            row.add(Long.toString(tier.lowerBoundInclusive()));
            row.add(tier.upperBoundExclusive() != null ? Long.toString(tier.upperBoundExclusive()) : null);
            row.add(tier.unitPrice().toPlainString());
            json.add(row);
        }
        return json;
    }

    private static List<PriceTier> tiersFromJson(List<List<String>> json) {
        List<PriceTier> tiers = new ArrayList<>();
        for (List<String> row : json) {
            Long upper = row.get(1) != null ? Long.parseLong(row.get(1)) : null;
            tiers.add(new PriceTier(Long.parseLong(row.get(0)), upper, new BigDecimal(row.get(2))));
        }
        return tiers;
    }

    /** Applies one committed event to the in-memory state (also used during replay). */
    private void apply(LogEvent event) {
        long seq = event.sequence();
        long ts = event.timestamp();
        String tenantId = event.tenantId();
        if (event instanceof TenantProvisionedEvent e) {
            MutableConfig cfg = new MutableConfig();
            cfg.cycle = SettlementCycle.valueOf(e.cycle());
            cfg.latePolicy = LateArrivalPolicy.valueOf(e.latePolicy());
            cfg.lateWindowMillis = e.lateWindowMillis();
            cfg.nextRuleVersion = 1L;
            cfg.updatedAt = ts;
            configs.put(tenantId, cfg);
            audit(tenantId, seq, ts, null, "TENANT_PROVISIONED",
                    "cycle=" + cfg.cycle + " latePolicy=" + cfg.latePolicy);
        } else if (event instanceof TenantConfiguredEvent e) {
            MutableConfig cfg = configs.get(tenantId);
            cfg.cycle = SettlementCycle.valueOf(e.cycle());
            cfg.latePolicy = LateArrivalPolicy.valueOf(e.latePolicy());
            cfg.lateWindowMillis = e.lateWindowMillis();
            cfg.updatedAt = ts;
            audit(tenantId, seq, ts, null, "TENANT_CONFIGURED",
                    "cycle=" + cfg.cycle + " latePolicy=" + cfg.latePolicy
                            + " lateWindowMillis=" + cfg.lateWindowMillis);
        } else if (event instanceof RuleConfiguredEvent e) {
            MutableConfig cfg = configs.get(tenantId);
            RuleVersion version = new RuleVersion(e.version(), e.effectiveAt(),
                    tiersFromJson(e.tiersJson()));
            cfg.rules.computeIfAbsent(e.metric(), k -> new ArrayList<>()).add(version);
            cfg.nextRuleVersion = Math.max(cfg.nextRuleVersion, e.version() + 1);
            cfg.updatedAt = ts;
            audit(tenantId, seq, ts, null, "RULE_CONFIGURED",
                    "metric=" + e.metric() + " version=" + e.version()
                            + " effectiveAt=" + e.effectiveAt());
        } else if (event instanceof RecordIngestedEvent e) {
            UsageRecord record = toRecord(e);
            dedup.computeIfAbsent(tenantId, k -> new HashMap<>()).put(e.dedupKey(), record);
            if (record.status() == RecordStatus.ACCEPTED) {
                PeriodState state = periods.computeIfAbsent(tenantId, k -> new TreeMap<>())
                        .computeIfAbsent(e.periodKey(),
                                k -> new PeriodState(tenantId, e.periodKey()));
                state.quantities().merge(e.metric(), e.quantity(), Long::sum);
                audit(tenantId, seq, ts, e.periodKey(), "RECORD_ACCEPTED",
                        "metric=" + e.metric() + " quantity=" + e.quantity()
                                + " dedupKey=" + e.dedupKey());
            } else {
                audit(tenantId, seq, ts, null, "RECORD_REJECTED",
                        "metric=" + e.metric() + " dedupKey=" + e.dedupKey()
                                + " reason=" + e.rejectReason());
            }
        } else if (event instanceof PeriodClosedEvent e) {
            PeriodState state = periods.computeIfAbsent(tenantId, k -> new TreeMap<>())
                    .computeIfAbsent(e.periodKey(), k -> new PeriodState(tenantId, e.periodKey()));
            state.setClosed(true);
            state.setClosedAt(ts);
            audit(tenantId, seq, ts, e.periodKey(), "PERIOD_CLOSED", "period closed");
        } else if (event instanceof PeriodReopenedEvent e) {
            PeriodState state = periods.get(tenantId).get(e.periodKey());
            state.setClosed(false);
            state.setClosedAt(0L);
            audit(tenantId, seq, ts, e.periodKey(), "PERIOD_REOPENED",
                    "period reopened for correction");
        } else if (event instanceof BillSettledEvent e) {
            PeriodState state = periods.get(tenantId).get(e.periodKey());
            state.setSettledRevision(e.bill().revision());
            bills.computeIfAbsent(tenantId, k -> new TreeMap<>()).put(e.periodKey(), e.bill());
            audit(tenantId, seq, ts, e.periodKey(), "BILL_SETTLED",
                    "revision=" + e.bill().revision() + " total=" + e.bill().totalAmount());
        }
    }

    private void audit(String tenantId, long seq, long ts, String periodKey,
                       String action, String detail) {
        audit.computeIfAbsent(tenantId, k -> new ArrayList<>())
                .add(new AuditEntry(seq, ts, tenantId, periodKey, action, detail));
    }
}
