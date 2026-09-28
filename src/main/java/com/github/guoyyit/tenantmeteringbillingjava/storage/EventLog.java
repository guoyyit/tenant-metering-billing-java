package com.github.guoyyit.tenantmeteringbillingjava.storage;

import com.github.guoyyit.tenantmeteringbillingjava.event.LogEvent;

import java.util.List;

/**
 * Durable, append-only write-ahead log of committed events.
 *
 * <p>Implementations must guarantee that once {@link #append(LogEvent)} returns
 * successfully the event survives a process crash, and that {@link #replay()} returns
 * each committed event exactly once in sequence order.
 */
public interface EventLog extends AutoCloseable {

    /**
     * Assigns the sequence (equal to 1-based position), persists the event durably
     * (fsync) and returns the same event populated with its sequence.
     *
     * @throws IllegalStateException if the log has been closed
     */
    LogEvent append(LogEvent event);

    /** Reads all committed events in sequence order. */
    List<LogEvent> replay();

    /** Last assigned sequence, 0 when empty. */
    long lastSequence();

    /** Flushes and releases file handles. */
    @Override
    void close();
}
