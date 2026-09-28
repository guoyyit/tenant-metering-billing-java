package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.util.Map;

/**
 * Immutable read view of one tenant-period: cumulative usage, open/closed state and
 * the latest settled bill revision. All fields come from the same committed snapshot.
 *
 * @param tenantId        tenant identifier
 * @param periodKey       period key
 * @param closed          whether the period is currently closed
 * @param settledRevision latest settled bill revision; 0 when never settled
 * @param quantities      cumulative accepted quantity per metric
 */
public record PeriodView(String tenantId,
                         String periodKey,
                         boolean closed,
                         int settledRevision,
                         Map<String, Long> quantities) {

    public PeriodView {
        quantities = Map.copyOf(quantities);
    }
}
