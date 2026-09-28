package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.Event;
import com.github.guoyyit.tenantmeteringbillingjava.event.EventType;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import com.github.guoyyit.tenantmeteringbillingjava.store.SnapshotStore;
import com.github.guoyyit.tenantmeteringbillingjava.store.WalLog;

import java.util.function.Supplier;

/**
 * 核心引擎：持有内存状态、WAL、快照与读写锁。
 *
 * <p>写事务：在写锁内执行业务校验与状态变更，提交时生成 {@link Event}，
 * 先 append WAL（fsync），再通过 {@link DefaultStateApplier} 应用到内存；
 * 若 fsync 前崩溃则什么都没发生，fsync 后崩溃则重启重放恢复。
 *
 * <p>读事务：在共享读锁内执行，取到的账单与累计用量必然来自同一状态版本。
 */
public class Engine {

    private BillingState state;
    private WalLog wal;
    private SnapshotStore snapshots;
    private StoreProperties properties;
    private DefaultStateApplier applier;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lock =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private int eventsSinceSnapshot;

    public void init(StoreProperties properties,
                     DefaultStateApplier applier,
                     RecoveryService recoveryService) {
        this.properties = properties;
        this.applier = applier;
        this.state = recoveryService.recover();
        this.wal = recoveryService.wal();
        this.snapshots = recoveryService.snapshots();
        this.eventsSinceSnapshot = 0;
    }

    public BillingState state() {
        return state;
    }

    /** 在共享读锁内执行只读操作。 */
    public <T> T read(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 在独占写锁内执行（不产生事件的内部操作）。 */
    public <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 提交一个变更事件：构造 payload -> WAL fsync -> 应用到内存 -> 视情况落快照。
     * 必须在写锁内调用。
     */
    public Event commit(EventType type, Supplier<Object> payloadSupplier) {
        long now = System.currentTimeMillis();
        long seq = wal.lastSeq() + 1;
        Object payload = payloadSupplier.get();
        Event event = new Event(seq, now, type, payload);
        wal.append(event);
        applier.apply(state, event);
        eventsSinceSnapshot++;
        if (eventsSinceSnapshot >= properties.getSnapshotEveryEvents()) {
            snapshots.save(state);
            eventsSinceSnapshot = 0;
        }
        return event;
    }
}
