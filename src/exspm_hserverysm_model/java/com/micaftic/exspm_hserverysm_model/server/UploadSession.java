package com.micaftic.exspm_hserverysm_model.server;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * In-memory state for one incoming model upload. Bytes are accumulated into a
 * single buffer as chunks arrive (chunks are bounded by the server-provided
 * chunk size and the client sends them sequentially, so a plain growing buffer
 * is sufficient).
 */
final class UploadSession {

    private final long uploadId;
    private final UUID owner;
    private final String modelId;
    private final String fileName;
    private final String fileKind;
    private final int totalBytes;
    private final String sha256;
    private final byte[] data;
    private int receivedBytes;
    private long lastTouchMillis;
    private boolean failed;

    UploadSession(long uploadId, UUID owner, String modelId, String fileName, int totalBytes, String sha256) {
        this.uploadId = uploadId;
        this.owner = owner;
        this.modelId = modelId;
        this.fileName = fileName;
        this.fileKind = fileKind(fileName);
        this.totalBytes = totalBytes;
        this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        this.data = new byte[totalBytes];
        this.lastTouchMillis = System.currentTimeMillis();
    }

    long uploadId() {
        return uploadId;
    }

    UUID owner() {
        return owner;
    }

    String modelId() {
        return modelId;
    }

    String fileName() {
        return fileName;
    }

    String fileKind() {
        return fileKind;
    }

    int totalBytes() {
        return totalBytes;
    }

    byte[] data() {
        return data;
    }

    String sha256() {
        return sha256;
    }

    int receivedBytes() {
        return receivedBytes;
    }

    boolean isFailed() {
        return failed;
    }

    void markFailed() {
        failed = true;
    }

    void touch() {
        lastTouchMillis = System.currentTimeMillis();
    }

    long lastTouchMillis() {
        return lastTouchMillis;
    }

    /**
     * Appends a chunk. Rejects out-of-order or overlapping writes defensively.
     *
     * @return false when the chunk was rejected (already failed or out of bounds).
     */
    boolean appendChunk(int offset, byte[] chunk) {
        if (failed || chunk == null) {
            return false;
        }
        if (offset < 0 || chunk.length == 0) {
            return false;
        }
        if (offset < receivedBytes || offset + chunk.length > totalBytes) {
            markFailed();
            return false;
        }
        System.arraycopy(chunk, 0, data, offset, chunk.length);
        receivedBytes = Math.max(receivedBytes, offset + chunk.length);
        touch();
        return true;
    }

    boolean isComplete() {
        return !failed && receivedBytes == totalBytes && totalBytes > 0;
    }

    private static String fileKind(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot + 1);
    }

    /** Copies of the accumulated bytes for final processing. */
    byte[] copyData() {
        return Arrays.copyOf(data, receivedBytes);
    }
}
