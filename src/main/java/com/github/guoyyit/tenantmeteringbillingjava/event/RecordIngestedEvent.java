package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.domain.RecordStatus;

/**
 * Terminal decision for one submitted usage record. Both accepted and rejected records
 * are logged; the dedup-key uniqueness guarantee comes from this log.
 *
 * @param periodKey attributed period; null for rejected records
 */
public record RecordIngestedEvent(long sequence,
                                  long timestamp,
                                  String tenantId,
                                  String metric,
                                  long occurredAt,
                                  String dedupKey,
                                  long quantity,
                                  long receivedAt,
                                  String status,
                                  String periodKey,
                                  String rejectReason) implements LogEvent {

    public RecordIngestedEvent(long sequence, long timestamp, String tenantId, String metric,
                               long occurredAt, String dedupKey, long quantity, long receivedAt,
                               RecordStatus status, String periodKey, String rejectReason) {
        this(sequence, timestamp, tenantId, metric, occurredAt, dedupKey, quantity, receivedAt,
                status.name(), periodKey, rejectReason);
    }
}
