package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.UsageAcceptedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.UsageRejectedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.model.IngestResult;
import com.github.guoyyit.tenantmeteringbillingjava.model.IngestStatus;
import com.github.guoyyit.tenantmeteringbillingjava.model.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.model.TenantConfig;
import com.github.guoyyit.tenantmeteringbillingjava.model.UsageRecord;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 调用记录写入。
 *
 * <p>去重：同一 (tenantId, dedupKey) 全局只计一次，重复写入返回 DUPLICATE 且结果确定。
 *
 * <p>迟到窗口：以接收时刻 receivedAt 为基准，若 occurTime &lt; receivedAt - lateWindow，
 * 判定为超期记录，按租户策略处理：
 * <ul>
 *   <li>REJECT：拒绝，写 USAGE_REJECTED 审计事件；</li>
 *   <li>CURRENT_PERIOD：归到 receivedAt 所在账期；</li>
 *   <li>NEAREST_WRITABLE：从自然账期向当前周期寻找第一个未关闭账期，全关闭则回退当前周期。</li>
 * </ul>
 * 未超期（含发生在未来的时钟漂移）记录归入 occurTime 的自然账期。
 */
@Service
public class IngestService {

    private final Engine engine;
    private final ConfigService configService;
    private final PeriodService periodService;

    public IngestService(Engine engine, ConfigService configService,
                         PeriodService periodService) {
        this.engine = engine;
        this.configService = configService;
        this.periodService = periodService;
    }

    public IngestResult ingest(String tenantId, String metricName,
                               long occurTimeEpochMs, String dedupKey) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BillingException("tenantId 不能为空");
        }
        if (metricName == null || metricName.isBlank()) {
            throw new BillingException("metricName 不能为空");
        }
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new BillingException("dedupKey 不能为空");
        }
        if (occurTimeEpochMs <= 0L) {
            throw new BillingException("occurTimeEpochMs 非法");
        }

        return engine.write(() -> {
            BillingState st = engine.state();
            TenantConfig config = configService.requireConfig(tenantId);
            String dk = BillingState.dedupKey(tenantId, dedupKey);
            UsageRecord existing = st.dedupIndex.get(dk);
            if (existing != null) {
                return new IngestResult(IngestStatus.DUPLICATE, existing.id(),
                        existing.periodId(), "dedupKey 已存在");
            }

            long receivedAt = System.currentTimeMillis();
            String naturalPeriod = periodService.periodIdOf(occurTimeEpochMs);
            String currentPeriod = periodService.periodIdOf(receivedAt);
            boolean overdue = occurTimeEpochMs
                    < receivedAt - config.lateWindowMinutes() * 60_000L;

            String reason = null;
            String assignedPeriod = naturalPeriod;

            if (overdue) {
                switch (config.lateArrivalPolicy()) {
                    case REJECT -> {
                        engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.USAGE_REJECTED,
                                () -> new UsageRejectedEvent(tenantId, metricName, dedupKey,
                                        occurTimeEpochMs, receivedAt,
                                        "超出迟到时间窗 " + config.lateWindowMinutes() + " 分钟"));
                        return new IngestResult(IngestStatus.REJECTED, null, null,
                                "超出迟到时间窗，按 REJECT 策略拒绝");
                    }
                    case CURRENT_PERIOD -> {
                        assignedPeriod = currentPeriod;
                        reason = "超期，按 CURRENT_PERIOD 归入当前账期";
                    }
                    case NEAREST_WRITABLE -> {
                        assignedPeriod = nearestWritablePeriod(st, config,
                                naturalPeriod, currentPeriod);
                        reason = "超期，按 NEAREST_WRITABLE 归入 " + assignedPeriod;
                    }
                }
            }

            // 关闭账期不允许再写入；策略归属时若目标账期已关闭，向最近可写账期前推
            assignedPeriod = ensureWritable(st, tenantId, assignedPeriod, currentPeriod);

            String id = UUID.randomUUID().toString();
            UsageRecord record = new UsageRecord(id, tenantId, metricName,
                    occurTimeEpochMs, receivedAt, dedupKey, assignedPeriod);
            engine.commit(com.github.guoyyit.tenantmeteringbillingjava.event.EventType.USAGE_ACCEPTED,
                    () -> new UsageAcceptedEvent(record));
            return new IngestResult(IngestStatus.ACCEPTED, id, assignedPeriod, reason);
        });
    }

    private String nearestWritablePeriod(BillingState st, TenantConfig config,
                                         String naturalPeriod, String currentPeriod) {
        String p = naturalPeriod;
        // 自然账期（或其后第一个未关闭账期）起，逐月向当前周期推进
        while (!p.equals(currentPeriod)) {
            if (!st.closedPeriods.contains(BillingState.periodKey(config.tenantId(), p))) {
                return p;
            }
            p = periodService.shift(p, 1);
        }
        return currentPeriod;
    }

    private String ensureWritable(BillingState st, String tenantId,
                                  String target, String currentPeriod) {
        String p = target;
        while (st.closedPeriods.contains(BillingState.periodKey(tenantId, p))
                && !p.equals(currentPeriod)) {
            p = periodService.shift(p, 1);
        }
        if (st.closedPeriods.contains(BillingState.periodKey(tenantId, p))) {
            throw new BillingException("目标账期已关闭且无可用可写账期: " + p, 409);
        }
        return p;
    }
}
