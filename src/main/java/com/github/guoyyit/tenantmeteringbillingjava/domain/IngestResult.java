package com.github.guoyyit.tenantmeteringbillingjava.domain;

/**
 * Result of submitting one usage record.
 *
 * @param accepted  whether the record was counted (duplicates are not counted)
 * @param duplicate true when the dedup key was already known
 * @param periodKey period the record was attributed to; null when rejected
 * @param reason    rejection / duplicate explanation
 */
public record IngestResult(boolean accepted, boolean duplicate, String periodKey, String reason) {
}
