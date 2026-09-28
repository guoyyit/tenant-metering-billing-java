package com.github.guoyyit.tenantmeteringbillingjava.event;

/** A period was closed. Closing an already closed period emits no new event (idempotent). */
public record PeriodClosedEvent(long sequence,
                                long timestamp,
                                String tenantId,
                                String periodKey) implements LogEvent {
}
