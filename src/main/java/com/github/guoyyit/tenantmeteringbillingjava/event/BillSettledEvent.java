package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;

/**
 * A bill was generated for a period. The whole bill is one atomic event: after recovery
 * either the complete bill exists or it does not, so a crash mid-settlement simply means
 * the settlement can be retried (or rolled back by reopening the period).
 */
public record BillSettledEvent(@JsonProperty("sequence") long sequence,
                               @JsonProperty("timestamp") long timestamp,
                               @JsonProperty("tenantId") String tenantId,
                               @JsonProperty("periodKey") String periodKey,
                               @JsonProperty("bill") Bill bill) implements LogEvent {
}
