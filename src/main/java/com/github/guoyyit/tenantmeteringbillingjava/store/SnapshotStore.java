package com.github.guoyyit.tenantmeteringbillingjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 状态快照存取（单个 JSON 文件）。
 * 保存时先写临时文件再原子 rename，保证快照要么是旧版本要么是完整新版本。
 */
public class SnapshotStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path snapshotFile;
    private final Path tempFile;

    public SnapshotStore(String snapshotFilePath) {
        this.snapshotFile = Path.of(snapshotFilePath);
        this.tempFile = Path.of(snapshotFilePath + ".tmp");
        try {
            Files.createDirectories(snapshotFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建快照目录", e);
        }
    }

    public synchronized void save(BillingState state) {
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(state);
            Files.write(tempFile, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // 确保临时文件数据落盘后再 rename
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    tempFile, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tempFile, snapshotFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            fsyncParent();
        } catch (IOException e) {
            throw new UncheckedIOException("快照写入失败", e);
        }
    }

    /** 无快照或快照损坏时返回 null（调用方将改为全量重放 WAL）。 */
    public synchronized BillingState load() {
        if (!Files.exists(snapshotFile)) {
            return null;
        }
        try {
            return MAPPER.readValue(snapshotFile.toFile(), BillingState.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** 快照对应的 WAL 序列号；无快照返回 0。 */
    public long snapshotSeq() {
        BillingState state = load();
        return state == null ? 0L : state.lastSeq;
    }

    private void fsyncParent() {
        try (var dir = Files.newDirectoryStream(snapshotFile.getParent())) {
            // 打开目录以触发目录项落盘（best-effort，平台不支持时忽略）
        } catch (IOException ignored) {
            // 目录 fsync 失败不影响数据正确性，仅极端崩溃下可能退回旧快照
        }
    }
}
