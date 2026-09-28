package com.github.guoyyit.tenantmeteringbillingjava.event;

import java.util.List;

/**
 * Pricing rule change for one metric.
 *
 * @param tiersJson JSON array of {@code [[lowerInclusive, upperExclusive|null, unitPriceString], ...]}
 */
public record RuleConfiguredEvent(long sequence,
                                  long timestamp,
                                  String tenantId,
                                  String metric,
                                  long version,
                                  long effectiveAt,
                                  List<List<String>> tiersJson) implements LogEvent {
}
