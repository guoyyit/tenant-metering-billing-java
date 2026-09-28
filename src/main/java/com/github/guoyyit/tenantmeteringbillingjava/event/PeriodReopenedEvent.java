package com.github.guoyyit.tenantmeteringbillingjava.event;

/** A closed period was reopened so that corrections or late records may be added. */
public record PeriodReopenedEvent(long sequence,
                                  long timestamp,
                                  String tenantId,
                                  String periodKey) implements LogEvent {
}
