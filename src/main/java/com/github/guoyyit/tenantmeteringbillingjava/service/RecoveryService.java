package com.github.guoyyit.tenantmeteringbillingjava.service;

import com.github.guoyyit.tenantmeteringbillingjava.event.Event;
import com.github.guoyyit.tenantmeteringbillingjava.store.BillingState;
import com.github.guoyyit.tenantmeteringbillingjava.store.SnapshotStore;
import com.github.guoyyit.tenantmeteringbillingjava.store.WalLog;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 崩溃恢复：启动时打开 WAL（自动截断尾部半行），加载最近快照，
 * 再确定性重放快照之后的事件，重建内存状态。
 *
 * <p>结算是单条 {@code BILL_SETTLED} 原子事件，WAL 中要么完整存在要么不存在，
 * 因此不存在“半个账单”，未完成的结算重启后直接回到“未结算”可重做。
 */
@Service
public class RecoveryService {

    private final StoreProperties properties;
    private final DefaultStateApplier applier;
    private WalLog wal;
    private SnapshotStore snapshots;

    public RecoveryService(StoreProperties properties, DefaultStateApplier applier) {
        this.properties = properties;
        this.applier = applier;
    }

    public WalLog wal() {
        ensureOpen();
        return wal;
    }

    public SnapshotStore snapshots() {
        ensureOpen();
        return snapshots;
    }

    public BillingState recover() {
        ensureOpen();
        BillingState state = snapshots.load();
        long fromSeq = 0L;
        if (state != null) {
            fromSeq = state.lastSeq;
        } else {
            state = new BillingState();
        }
        for (Event event : wal.readAll()) {
            if (event.seq() > fromSeq) {
                applier.apply(state, event);
            }
        }
        return state;
    }

    private void ensureOpen() {
        if (wal == null) {
            Path dir = Path.of(properties.getDataDir());
            this.wal = new WalLog(dir.resolve("wal.log").toString());
            this.snapshots = new SnapshotStore(dir.resolve("snapshot.json").toString());
        }
    }
}
