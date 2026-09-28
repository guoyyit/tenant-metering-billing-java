package com.github.guoyyit.tenantmeteringbillingjava.tests.support;

import com.github.guoyyit.tenantmeteringbillingjava.service.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.service.ConfigService;
import com.github.guoyyit.tenantmeteringbillingjava.service.DefaultStateApplier;
import com.github.guoyyit.tenantmeteringbillingjava.service.Engine;
import com.github.guoyyit.tenantmeteringbillingjava.service.IngestService;
import com.github.guoyyit.tenantmeteringbillingjava.service.PeriodLifecycleService;
import com.github.guoyyit.tenantmeteringbillingjava.service.PeriodService;
import com.github.guoyyit.tenantmeteringbillingjava.service.RecoveryService;
import com.github.guoyyit.tenantmeteringbillingjava.service.StoreProperties;

import java.nio.file.Path;

/**
 * 在独立临时目录上装配一套服务实例，不启动 Spring 容器，速度快且互不干扰。
 */
public class TestEnv {

    public final Engine engine;
    public final ConfigService configService;
    public final IngestService ingestService;
    public final BillingService billingService;
    public final PeriodLifecycleService lifecycleService;
    public final RecoveryService recoveryService;
    public final PeriodService periodService = new PeriodService();
    public final Path dataDir;

    public TestEnv(Path dataDir) {
        this(dataDir, 1000);
    }

    public TestEnv(Path dataDir, int snapshotEveryEvents) {
        this.dataDir = dataDir;
        StoreProperties props = new StoreProperties();
        props.setDataDir(dataDir.toString());
        props.setSnapshotEveryEvents(snapshotEveryEvents);
        DefaultStateApplier applier = new DefaultStateApplier();
        RecoveryService recovery = new RecoveryService(props, applier);
        Engine eng = new Engine();
        eng.init(props, applier, recovery);
        this.engine = eng;
        this.recoveryService = recovery;
        this.configService = new ConfigService(eng);
        this.ingestService = new IngestService(eng, configService, periodService);
        this.billingService = new BillingService(eng, configService);
        this.lifecycleService = new PeriodLifecycleService(eng, configService);
    }

    /** 模拟重启：丢弃旧实例，基于同一数据目录重建并恢复。 */
    public TestEnv restart() {
        return new TestEnv(dataDir);
    }
}
