package com.github.guoyyit.tenantmeteringbillingjava.event;

/** Settlement cycle / late policy / late window change. */
public record TenantConfiguredEvent(long sequence,
                                    long timestamp,
                                    String tenantId,
                                    String cycle,
                                    String latePolicy,
                                    long lateWindowMillis) implements LogEvent {
}
