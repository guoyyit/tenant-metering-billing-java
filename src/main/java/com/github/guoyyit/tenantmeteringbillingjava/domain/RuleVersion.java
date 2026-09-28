package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.util.List;

/**
 * An immutable version of the pricing rule for one metric.
 *
 * @param version      monotonically increasing version, assigned when the rule is configured
 * @param effectiveAt  instant from which the rule applies to not-yet-settled periods
 * @param tiers        tiered unit prices; must be sorted and non-overlapping
 */
public record RuleVersion(long version, long effectiveAt, List<PriceTier> tiers) {
}
