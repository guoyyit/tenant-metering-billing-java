package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * An immutable bill for one tenant-period. Re-opening a period and re-settling it
 * produces a new bill with a higher revision; previously generated bills stay stored.
 *
 * @param tenantId     tenant identifier
 * @param periodKey    billed period key
 * @param revision     1-based revision, incremented on every settlement of this period
 * @param lines        per metric bill lines
 * @param totalAmount  sum of line amounts
 * @param createdAt    generation instant (epoch millis)
 */
public record Bill(String tenantId,
                   String periodKey,
                   int revision,
                   List<BillLine> lines,
                   BigDecimal totalAmount,
                   long createdAt) {

    public Bill {
        lines = List.copyOf(lines);
    }
}
