package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.math.BigDecimal;

/**
 * One metric line of a bill, with the pricing rule snapshot used.
 *
 * @param metric       metric name
 * @param quantity     billed quantity
 * @param ruleVersion  rule version used for pricing
 * @param amount       computed amount
 */
public record BillLine(String metric, long quantity, long ruleVersion, BigDecimal amount) {
}
