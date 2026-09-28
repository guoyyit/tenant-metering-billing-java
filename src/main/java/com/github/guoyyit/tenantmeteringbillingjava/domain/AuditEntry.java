package com.github.guoyyit.tenantmeteringbillingjava.domain;

/**
 * One auditable state change.
 *
 * @param sequence    global monotonic sequence number
 * @param timestamp   event instant (epoch millis)
 * @param tenantId    related tenant; null for system events
 * @param periodKey   related period; may be null
 * @param action      short action name (RECORD_ACCEPTED, RECORD_REJECTED, PERIOD_CLOSED, ...)
 * @param detail      human readable detail
 */
public record AuditEntry(long sequence,
                         long timestamp,
                         String tenantId,
                         String periodKey,
                         String action,
                         String detail) {
}
