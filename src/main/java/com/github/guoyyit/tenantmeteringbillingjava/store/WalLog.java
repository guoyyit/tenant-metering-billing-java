package com.github.guoyyit.tenantmeteringbillingjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.tenantmeteringbillingjava.event.BillSettledEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.Event;
import com.github.guoyyit.tenantmeteringbillingjava.event.EventType;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodReopenedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RulePutEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.UsageAcceptedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.UsageRejectedEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 追加式预写日志（JSON Lines，每行一条事件）。
 * append 返回前执行 flush + FileChannel.force(true)，保证已提交事件崩溃不丢。
 * 若崩溃发生在写入半行期间，恢复时会截断并丢弃该半行（属未提交内容）。
 */
public class WalLog implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 关键：payload 以 Object 序列化/解析时保留十进制标度，
            // 否则 4.00 会被转成 double 再变回 4.0
            .configure(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .USE_BIG_DECIMAL_FOR_FLOATS, true);

    private final Path logFile;
    private final RandomAccessFile raf;
    private long lastSeq;

    public WalLog(String logFilePath) {
        try {
            this.logFile = Path.of(logFilePath);
            Files.createDirectories(logFile.getParent());
            this.raf = new RandomAccessFile(logFilePath, "rw");
            this.lastSeq = scanLastSeq();
        } catch (IOException e) {
            throw new UncheckedIOException("无法打开 WAL: " + logFilePath, e);
        }
    }

    /** 追加事件并持久化（flush + fsync）。 */
    public synchronized void append(Event event) {
        try {
            Line line = Line.of(event);
            byte[] bytes = (MAPPER.writeValueAsString(line) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            raf.seek(raf.length());
            raf.write(bytes);
            raf.getFD().sync();
            this.lastSeq = event.seq();
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 追加失败", e);
        }
    }

    /** 读取全部完整事件；遇到损坏/半截行截断文件尾部后停止。 */
    public synchronized List<Event> readAll() {
        List<Event> events = new ArrayList<>();
        long validBytes = 0L;
        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String raw;
            long offset = 0L;
            while ((raw = reader.readLine()) != null) {
                long nextOffset = offset + raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                if (!raw.isBlank()) {
                    Event event = parse(raw);
                    if (event == null) {
                        break;
                    }
                    events.add(event);
                    validBytes = nextOffset;
                }
                offset = nextOffset;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 读取失败", e);
        }
        truncateTail(validBytes);
        return events;
    }

    private Event parse(String line) {
        try {
            return MAPPER.readValue(line, Line.class).toEvent();
        } catch (Exception e) {
            return null;
        }
    }

    private long scanLastSeq() throws IOException {
        long seq = 0L;
        for (Event event : readAll()) {
            seq = Math.max(seq, event.seq());
        }
        return seq;
    }

    private void truncateTail(long validBytes) {
        try {
            long length = raf.length();
            if (length != validBytes) {
                try (FileChannel channel = FileChannel.open(logFile,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    channel.truncate(validBytes);
                    channel.force(true);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("WAL 截断失败", e);
        }
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    @Override
    public synchronized void close() {
        try {
            raf.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * WAL 单行。payload 按事件类型展开为强类型字段，避免 Jackson 把
     * Object 中的 BigDecimal 转成 double 而丢失金额标度。
     */
    private static final class Line {
        public long seq;
        @com.fasterxml.jackson.annotation.JsonProperty("timestamp")
        public long timestampEpochMs;
        public EventType type;
        public TenantConfiguredEvent tenantConfigured;
        public RulePutEvent rulePut;
        public UsageAcceptedEvent usageAccepted;
        public UsageRejectedEvent usageRejected;
        public PeriodClosedEvent periodClosed;
        public PeriodReopenedEvent periodReopened;
        public BillSettledEvent billSettled;

        static Line of(Event event) {
            Line line = new Line();
            line.seq = event.seq();
            line.timestampEpochMs = event.timestampEpochMs();
            line.type = event.type();
            switch (event.type()) {
                case TENANT_CONFIGURED -> line.tenantConfigured = (TenantConfiguredEvent) event.payload();
                case RULE_PUT -> line.rulePut = (RulePutEvent) event.payload();
                case USAGE_ACCEPTED -> line.usageAccepted = (UsageAcceptedEvent) event.payload();
                case USAGE_REJECTED -> line.usageRejected = (UsageRejectedEvent) event.payload();
                case PERIOD_CLOSED -> line.periodClosed = (PeriodClosedEvent) event.payload();
                case PERIOD_REOPENED -> line.periodReopened = (PeriodReopenedEvent) event.payload();
                case BILL_SETTLED -> line.billSettled = (BillSettledEvent) event.payload();
            }
            return line;
        }

        Event toEvent() {
            Object payload = switch (type) {
                case TENANT_CONFIGURED -> tenantConfigured;
                case RULE_PUT -> rulePut;
                case USAGE_ACCEPTED -> usageAccepted;
                case USAGE_REJECTED -> usageRejected;
                case PERIOD_CLOSED -> periodClosed;
                case PERIOD_REOPENED -> periodReopened;
                case BILL_SETTLED -> billSettled;
            };
            return new Event(seq, timestampEpochMs, type, payload);
        }
    }
}
