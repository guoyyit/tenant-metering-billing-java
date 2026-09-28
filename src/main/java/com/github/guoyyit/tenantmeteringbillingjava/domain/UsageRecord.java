package com.github.guoyyit.tenantmeteringbillingjava.domain;

/**
 * One reported API usage event.
 *
 * @param tenantId    tenant identifier
 * @param metric      metric name
 * @param occurredAt  time the usage actually happened (epoch millis)
 * @param dedupKey    client supplied idempotency key; (tenantId, dedupKey) is unique
 * @param quantity    usage quantity; must be positive
 * @param receivedAt  time the service received the record (epoch millis)
 * @param status      accepted or rejected
 * @param periodKey   period the record was attributed to; null when rejected
 * @param rejectReason human readable reason when rejected
 */
public record UsageRecord(String tenantId,
                          String metric,
                          long occurredAt,
                          String dedupKey,
                          long quantity,
                          long receivedAt,
                          RecordStatus status,
                          String periodKey,
                          String rejectReason) {
}
