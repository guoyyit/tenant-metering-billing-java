package com.github.guoyyit.tenantmeteringbillingjava.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties under prefix {@code metering}.
 */
@Component
@ConfigurationProperties(prefix = "metering")
public class BillingProperties {

    /** Directory holding the write-ahead log file. */
    private String dataDir = "data";

    /** Log file name inside dataDir. */
    private String logFile = "events.log";

    /** Default late-arrival window in milliseconds for auto-provisioned tenants. */
    private long defaultLateWindowMillis = 7L * 24 * 60 * 60 * 1000;

    /** Default settlement cycle for auto-provisioned tenants. */
    private String defaultCycle = "MONTHLY";

    /** Default late-arrival policy for auto-provisioned tenants. */
    private String defaultLatePolicy = "NEAREST_WRITABLE_PERIOD";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getLogFile() {
        return logFile;
    }

    public void setLogFile(String logFile) {
        this.logFile = logFile;
    }

    public long getDefaultLateWindowMillis() {
        return defaultLateWindowMillis;
    }

    public void setDefaultLateWindowMillis(long defaultLateWindowMillis) {
        this.defaultLateWindowMillis = defaultLateWindowMillis;
    }

    public String getDefaultCycle() {
        return defaultCycle;
    }

    public void setDefaultCycle(String defaultCycle) {
        this.defaultCycle = defaultCycle;
    }

    public String getDefaultLatePolicy() {
        return defaultLatePolicy;
    }

    public void setDefaultLatePolicy(String defaultLatePolicy) {
        this.defaultLatePolicy = defaultLatePolicy;
    }
}
