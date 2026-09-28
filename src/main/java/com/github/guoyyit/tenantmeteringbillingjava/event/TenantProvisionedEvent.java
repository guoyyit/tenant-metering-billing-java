package com.github.guoyyit.tenantmeteringbillingjava.event;

/** First event of every tenant; records its default configuration. */
public record TenantProvisionedEvent(long sequence,
                                     long timestamp,
                                     String tenantId,
                                     String cycle,
                                     String latePolicy,
                                     long lateWindowMillis) implements LogEvent {
}
