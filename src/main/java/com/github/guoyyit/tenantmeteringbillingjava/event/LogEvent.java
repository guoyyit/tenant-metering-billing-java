package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.LateArrivalPolicy;
import com.github.guoyyit.tenantmeteringbillingjava.domain.RecordStatus;
import com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle;

/**
 * A single durable state change. Every committed mutation of the billing engine is
 * appended to the write-ahead log as exactly one {@code LogEvent}, so recovery can
 * rebuild all state by replaying events in sequence order.
 */
public sealed interface LogEvent
        permits TenantProvisionedEvent, TenantConfiguredEvent, RuleConfiguredEvent,
        RecordIngestedEvent, PeriodClosedEvent, PeriodReopenedEvent, BillSettledEvent {

    /** Global monotonic sequence number. */
    long sequence();

    /** Event instant, epoch millis. */
    long timestamp();

    /** Tenant this event belongs to (null for system events). */
    String tenantId();
}
