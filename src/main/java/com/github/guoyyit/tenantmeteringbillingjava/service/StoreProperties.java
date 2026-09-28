package com.github.guoyyit.tenantmeteringbillingjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地持久化配置项，前缀 billing.storage。
 */
@ConfigurationProperties(prefix = "billing.storage")
public class StoreProperties {

    /** WAL 与快照所在目录 */
    private String dataDir = "./data/billing";
    /** 每提交多少个事件落一次快照 */
    private int snapshotEveryEvents = 1000;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public int getSnapshotEveryEvents() { return snapshotEveryEvents; }
    public void setSnapshotEveryEvents(int snapshotEveryEvents) { this.snapshotEveryEvents = snapshotEveryEvents; }
}
