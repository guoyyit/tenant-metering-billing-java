package com.github.guoyyit.tenantmeteringbillingjava.domain;

/**
 * Policy for usage records that arrive after the target period's write window.
 */
public enum LateArrivalPolicy {
    /** Reject the record (it is recorded in the audit trail as REJECTED). */
    REJECT,
    /** Attribute the record to the current open period. */
    CURRENT_PERIOD,
    /** Attribute the record to the most recent still-writable period. */
    NEAREST_WRITABLE_PERIOD
}
