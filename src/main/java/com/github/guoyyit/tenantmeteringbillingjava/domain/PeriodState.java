package com.github.guoyyit.tenantmeteringbillingjava.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable internal state of one tenant-period.
 *
 * <p>Only mutated while holding the engine write lock, and only changed through
 * committed log events.
 */
public final class PeriodState {

    private final String tenantId;
    private final String periodKey;
    private boolean closed;
    private long closedAt;
    private int settledRevision;
    /** Accepted quantity per metric. */
    private final Map<String, Long> quantities = new HashMap<>();

    public PeriodState(String tenantId, String periodKey) {
        this.tenantId = tenantId;
        this.periodKey = periodKey;
    }

    public String tenantId() {
        return tenantId;
    }

    public String periodKey() {
        return periodKey;
    }

    public boolean closed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public long closedAt() {
        return closedAt;
    }

    public void setClosedAt(long closedAt) {
        this.closedAt = closedAt;
    }

    public int settledRevision() {
        return settledRevision;
    }

    public void setSettledRevision(int settledRevision) {
        this.settledRevision = settledRevision;
    }

    public Map<String, Long> quantities() {
        return quantities;
    }
}
