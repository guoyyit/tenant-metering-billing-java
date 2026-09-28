package com.github.guoyyit.tenantmeteringbillingjava.store;

import com.github.guoyyit.tenantmeteringbillingjava.event.Event;

/**
 * 把一条已提交事件应用到内存状态。实现必须是确定性的、纯内存操作（不做 IO）。
 */
public interface StateApplier {
    void apply(BillingState state, Event event);
}
