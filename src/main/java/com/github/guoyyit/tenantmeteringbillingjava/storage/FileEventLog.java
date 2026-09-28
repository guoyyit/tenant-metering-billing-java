package com.github.guoyyit.tenantmeteringbillingjava.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.tenantmeteringbillingjava.billing.BillingProperties;
import com.github.guoyyit.tenantmeteringbillingjava.event.BillSettledEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.LogEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodClosedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.PeriodReopenedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RecordIngestedEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.RuleConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantConfiguredEvent;
import com.github.guoyyit.tenantmeteringbillingjava.event.TenantProvisionedEvent;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * File backed {@link EventLog}.
 *
 * <p>Line format (one JSON object per line, UTF-8):
 * {@code {"seq":1,"crc":<crc32 of payload bytes>,"type":"...","payload":{...}}}
 *
 * <p>Durability: each {@link #append(LogEvent)} fsyncs both data and the directory, so a
 * committed event survives an OS crash. Recovery: on start the file is read front to
 * back; any line that is partial, fails the checksum or is of an unknown type marks the
 * end of the valid log and the file is truncated there. Because each append is a single
 * write smaller than the typical pipe buffer and ends with {@code \n}, torn appends are
 * confined to the tail.
 *
 * <p>A {@code .lock} side file with an exclusive JVM file lock prevents two service
 * instances opening the same data directory.
 */
@Component
public class FileEventLog implements EventLog {

    private record Line(long seq, long crc, String type, LogEvent payload) {
    }

    private static final Map<String, Class<? extends LogEvent>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("tenant-provisioned", TenantProvisionedEvent.class);
        TYPES.put("tenant-configured", TenantConfiguredEvent.class);
        TYPES.put("rule-configured", RuleConfiguredEvent.class);
        TYPES.put("record-ingested", RecordIngestedEvent.class);
        TYPES.put("period-closed", PeriodClosedEvent.class);
        TYPES.put("period-reopened", PeriodReopenedEvent.class);
        TYPES.put("bill-settled", BillSettledEvent.class);
    }

    private final ObjectMapper mapper;
    private final Path file;
    private final Path lockFile;
    private FileChannel channel;
    private FileLock fileLock;
    private long lastSequence;

    public FileEventLog(BillingProperties properties, ObjectMapper mapper) {
        this.file = Path.of(properties.getDataDir(), properties.getLogFile());
        this.lockFile = Path.of(properties.getDataDir(), properties.getLogFile() + ".lock");
        this.mapper = mapper;
        open();
    }

    /** Opens (creating if needed), takes the JVM lock and truncates any torn tail. */
    synchronized void open() {
        try {
            Files.createDirectories(file.getParent());
            this.channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileChannel lockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            this.fileLock = lockChannel.tryLock();
            if (fileLock == null) {
                lockChannel.close();
                throw new IllegalStateException("another service instance already holds " + lockFile);
            }
            recover();
            fsyncParent();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open event log " + file, e);
        }
    }

    /**
     * Best-effort fsync of the parent directory so that a freshly created log or lock file
     * and any truncation survive an OS power loss. Failures are ignored on platforms that
     * do not support opening directories for writing.
     */
    private void fsyncParent() {
        try (FileChannel dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // directory fsync not supported on this platform/JDK
        }
    }

    /**
     * Reads and validates every line, truncates the file at the first bad or unexpected
     * tail line (partial write after a crash). The channel is positioned at the valid end.
     */
    private void recover() throws IOException {
        byte[] all = Files.readAllBytes(file);
        long maxSeq = 0L;
        long validEnd = 0L;
        int lineStart = 0;
        int i = 0;
        while (i < all.length) {
            int lineEnd = i;
            while (lineEnd < all.length && all[lineEnd] != '\n') {
                lineEnd++;
            }
            boolean bad;
            if (lineEnd == lineStart) {
                bad = false; // tolerate blank lines
            } else {
                byte[] raw = new byte[lineEnd - lineStart];
                System.arraycopy(all, lineStart, raw, 0, raw.length);
                Line line = decode(raw);
                bad = line == null || line.seq() != maxSeq + 1;
                if (!bad) {
                    maxSeq = line.seq();
                }
            }
            if (bad) {
                break;
            }
            validEnd = lineEnd < all.length ? lineEnd + 1L : lineEnd;
            i = lineEnd + 1;
            lineStart = i;
        }
        if (validEnd < all.length) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(validEnd);
                ch.force(true);
            }
            fsyncParent();
        }
        this.lastSequence = maxSeq;
        channel.position(validEnd);
    }

    @Override
    public synchronized LogEvent append(LogEvent event) {
        if (channel == null) {
            throw new IllegalStateException("event log is closed");
        }
        long seq = lastSequence + 1;
        try {
            String type = typeOf(event);
            byte[] payload = mapper.writeValueAsBytes(event);
            CRC32 crc = new CRC32();
            crc.update(payload);
            // Embed the payload verbatim (not re-serialized through a JsonNode) so the
            // CRC computed by readers over identical bytes always matches.
            String payloadJson = new String(payload, StandardCharsets.UTF_8);
            String lineText = "{\"seq\":" + seq
                    + ",\"crc\":" + crc.getValue()
                    + ",\"type\":\"" + type + "\""
                    + ",\"payload\":" + payloadJson + "}";
            byte[] line = lineText.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream(line.length + 1);
            out.write(line);
            out.write('\n');
            byte[] bytes = out.toByteArray();
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
            lastSequence = seq;
            return withSequence(event, seq);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append event", e);
        }
    }

    @Override
    public synchronized List<LogEvent> replay() {
        try {
            List<LogEvent> events = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String raw;
                while ((raw = reader.readLine()) != null) {
                    if (raw.isBlank()) {
                        continue;
                    }
                    Line line = decode(raw.getBytes(StandardCharsets.UTF_8));
                    if (line == null) {
                        break;
                    }
                    events.add(line.payload());
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to replay event log", e);
        }
    }

    @Override
    public synchronized long lastSequence() {
        return lastSequence;
    }

    @Override
    public synchronized void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.force(true);
            channel.close();
            if (fileLock != null) {
                fileLock.channel().close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close event log", e);
        } finally {
            channel = null;
            fileLock = null;
        }
    }

    private Line decode(byte[] raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            long seq = root.path("seq").asLong();
            long crc = root.path("crc").asLong();
            String type = root.path("type").asText(null);
            Class<? extends LogEvent> typeClass = TYPES.get(type);
            if (typeClass == null) {
                return null;
            }
            // CRC is verified over the exact embedded payload bytes. We must not
            // re-serialize the JsonNode: Jackson normalizes numbers on write (21.00 ->
            // 21.0), which changes bytes and corrupts the checksum. Slice the raw JSON
            // value out of the line via brace matching instead.
            byte[] payload = slicePayload(raw);
            CRC32 actual = new CRC32();
            actual.update(payload);
            if (actual.getValue() != crc) {
                return null;
            }
            LogEvent event = mapper.readValue(payload, typeClass);
            return new Line(seq, crc, type, event);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the raw bytes of the {@code "payload"} JSON value from an envelope line.
     * The payload is the last top-level field; the value (object or array) is located
     * after {@code "payload":} and sliced up to its matching closing bracket.
     */
    private static byte[] slicePayload(byte[] raw) {
        byte[] needle = "\"payload\"".getBytes(StandardCharsets.UTF_8);
        int i = indexOf(raw, needle, 0);
        if (i < 0) {
            throw new IllegalStateException("payload field missing");
        }
        i += needle.length;
        while (i < raw.length && (raw[i] == ':' || raw[i] == ' ' || raw[i] == '\t')) {
            i++;
        }
        int start = i;
        byte open = raw[start];
        byte close = (byte) (open == '{' ? '}' : ']');
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (; i < raw.length; i++) {
            byte b = raw[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
                continue;
            }
            if (b == '"') {
                inString = true;
            } else if (b == open) {
                depth++;
            } else if (b == close) {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            }
        }
        byte[] payload = new byte[i - start];
        System.arraycopy(raw, start, payload, 0, payload.length);
        return payload;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String typeOf(LogEvent event) {
        for (Map.Entry<String, Class<? extends LogEvent>> entry : TYPES.entrySet()) {
            if (entry.getValue().isInstance(event)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("unknown event type " + event.getClass());
    }

    private LogEvent withSequence(LogEvent event, long seq) {
        // Events carry their sequence in the record header; rebuild via Jackson round trip
        // by setting the field through the dedicated constructor forms below.
        if (event instanceof TenantProvisionedEvent e) {
            return (LogEvent) new TenantProvisionedEvent(seq, e.timestamp(), e.tenantId(),
                    e.cycle(), e.latePolicy(), e.lateWindowMillis());
        }
        if (event instanceof TenantConfiguredEvent e) {
            return new TenantConfiguredEvent(seq, e.timestamp(), e.tenantId(), e.cycle(),
                    e.latePolicy(), e.lateWindowMillis());
        }
        if (event instanceof RuleConfiguredEvent e) {
            return new RuleConfiguredEvent(seq, e.timestamp(), e.tenantId(), e.metric(),
                    e.version(), e.effectiveAt(), e.tiersJson());
        }
        if (event instanceof RecordIngestedEvent e) {
            return new RecordIngestedEvent(seq, e.timestamp(), e.tenantId(), e.metric(),
                    e.occurredAt(), e.dedupKey(), e.quantity(), e.receivedAt(), e.status(),
                    e.periodKey(), e.rejectReason());
        }
        if (event instanceof PeriodClosedEvent e) {
            return new PeriodClosedEvent(seq, e.timestamp(), e.tenantId(), e.periodKey());
        }
        if (event instanceof PeriodReopenedEvent e) {
            return new PeriodReopenedEvent(seq, e.timestamp(), e.tenantId(), e.periodKey());
        }
        if (event instanceof BillSettledEvent e) {
            return new BillSettledEvent(seq, e.timestamp(), e.tenantId(), e.periodKey(), e.bill());
        }
        throw new IllegalArgumentException("unknown event type " + event.getClass());
    }
}
