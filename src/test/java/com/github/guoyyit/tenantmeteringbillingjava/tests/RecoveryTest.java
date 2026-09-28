package com.github.guoyyit.tenantmeteringbillingjava.tests;

import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingService;
import com.github.guoyyit.tenantmeteringbillingjava.domain.Bill;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PeriodView;
import com.github.guoyyit.tenantmeteringbillingjava.domain.PriceTier;
import com.github.guoyyit.tenantmeteringbillingjava.storage.EventLog;
import com.github.guoyyit.tenantmeteringbillingjava.storage.FileEventLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash recovery: committed records, closed periods and settled bills survive a restart
 * exactly once; a torn/corrupt trailing line (simulating a crash mid-append) is
 * truncated and the valid prefix is recovered.
 */
class RecoveryTest {

    private BillingProperties props(Path dir) {
        BillingProperties props = new BillingProperties();
        props.setDataDir(dir.toString());
        props.setDefaultCycle("MONTHLY");
        props.setDefaultLatePolicy("NEAREST_WRITABLE_PERIOD");
        return props;
    }

    @Test
    void restartRebuildsAllCommittedStateWithoutDuplication() {
        Path dir = Path.of("target/test-recovery-" + System.nanoTime());
        BillingProperties p1 = props(dir);
        String t = "tr";
        String period;
        long occurredAt;
        long auditCountAfterFirstRun;

        try (FileEventLog log = new FileEventLog(p1, new ObjectMapper())) {
            BillingService svc = new BillingService(log, p1);
            svc.init();
            occurredAt = System.currentTimeMillis();
            svc.configureRule(t, "m", null, List.of(
                    new PriceTier(0L, null, new BigDecimal("3.00"))));
            svc.ingest(t, "m", occurredAt, "a", 2);
            svc.ingest(t, "m", occurredAt, "b", 5);
            period = com.github.guoyyit.tenantmeteringbillingjava.billing.PeriodCalculator
                    .currentPeriodKey(com.github.guoyyit.tenantmeteringbillingjava.domain.SettlementCycle.MONTHLY);
            svc.closePeriod(t, period);
            Bill bill = svc.settle(t, period);
            assertEquals(1, bill.revision());
            auditCountAfterFirstRun = svc.audit(t).size();
        }

        // Simulate a brand new process opening the same directory.
        BillingProperties p2 = props(dir);
        try (FileEventLog log2 = new FileEventLog(p2, new ObjectMapper())) {
            BillingService restarted = new BillingService(log2, p2);
            restarted.init();
            PeriodView view = restarted.getPeriod(t, period);
            assertTrue(view.closed(), "closed state must survive restart");
            assertEquals(7L, view.quantities().get("m"), "committed usage survives restart");
            assertEquals(1, view.settledRevision());
            Bill restored = restarted.getBill(t, period);
            assertEquals(0, new BigDecimal("21.00").compareTo(restored.totalAmount()));

            // Replaying old dedup keys after restart still counts once and emits no
            // additional committed record / audit entry.
            var dup = restarted.ingest(t, "m", occurredAt, "a", 2);
            assertTrue(dup.duplicate());
            assertEquals(7L, restarted.getPeriod(t, period).quantities().get("m"));
            assertEquals(auditCountAfterFirstRun, restarted.audit(t).size(),
                    "duplicate replay after restart must not add audit entries");
        }
    }

    @Test
    @SuppressWarnings("resource")
    void tornTailLineIsTruncatedOnRecovery() throws Exception {
        Path dir = Path.of("target/test-recovery-torn-" + System.nanoTime());
        BillingProperties p = props(dir);
        String t = "torn";
        try (FileEventLog log = new FileEventLog(p, new ObjectMapper())) {
            BillingService svc = new BillingService(log, p);
            svc.init();
            svc.ingest(t, "m", System.currentTimeMillis(), "good", 1);
        }
        Path logFile = dir.resolve(p.getLogFile());
        long goodSize = Files.size(logFile);
        Files.writeString(logFile, "{\"seq\":99,\"crc\":1,\"type\":\"record-ingested\","
                + "\"payload\":{\"torn\":tru", StandardOpenOption.APPEND);
        long tornSize = Files.size(logFile);

        try (FileEventLog log = new FileEventLog(p, new ObjectMapper())) {
            List<com.github.guoyyit.tenantmeteringbillingjava.event.LogEvent> events = log.replay();
            assertEquals(2, events.size(), "provisioning + one good record survive");
            assertEquals(2L, log.lastSequence());
        }
        assertEquals(goodSize, Files.size(logFile), "torn tail truncated to last valid line end");
        assertTrue(tornSize > goodSize);
    }
}
