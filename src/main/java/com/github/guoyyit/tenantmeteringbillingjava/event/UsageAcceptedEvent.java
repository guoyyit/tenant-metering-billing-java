package com.github.guoyyit.tenantmeteringbillingjava.event;

import com.github.guoyyit.tenantmeteringbillingjava.model.UsageRecord;

public record UsageAcceptedEvent(UsageRecord record) {
}
