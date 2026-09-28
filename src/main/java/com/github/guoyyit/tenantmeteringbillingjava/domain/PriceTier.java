package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.math.BigDecimal;

/**
 * A single tier of a tiered unit price.
 *
 * @param lowerBoundInclusive inclusive lower bound of the tier (units)
 * @param upperBoundExclusive exclusive upper bound; null means open ended
 * @param unitPrice           price per unit inside this tier
 */
public record PriceTier(long lowerBoundInclusive, Long upperBoundExclusive, BigDecimal unitPrice) {
}
