package com.micaftic.morpher.client.upload;

import com.micaftic.morpher.client.ClientModelManager;
import com.micaftic.morpher.core.api.network.state.CloudState;
import com.micaftic.morpher.core.api.network.upload.ModelUploadTransport;
import com.micaftic.morpher.legacy.compat.LegacyCompatModelFormat;
import com.micaftic.morpher.network.NetworkHandler;
import com.micaftic.morpher.util.DigestUtil;
import com.micaftic.morpher.util.PerformanceProfiler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One asynchronous upload to the configured SPM Cloud instance, with a fallback to the
 * standalone {@code exspm_hserverysm_model} server channel.
 *
 * <p>Two transports coexist:</p>
 * <ul>
 *   <li><b>Cloud</b> (default): {@link CloudUploadRuntime#transport()} streams a file to the
 *       SPM Cloud API; one asynchronous {@code upload(...)} call, progress + completion via
 *       the returned future.</li>
 *   <li><b>Standalone channel</b> (DragonVer): when the server negotiates the
 *       {@code exspm_hserverysm_model:1} channel (standalone server-only upload mod),
 *       uploads go straight into the official YSM custom folder via
 *       {@link YsmUploadTransport}. This path keeps the start/chunk/finish packet state
 *       machine driven by {@link #tickCurrent()} and acknowledged by
 *       {@link #onStartAck(long, byte, int, int, int, String)} /
 *       {@link #onResult(long, byte, String, long, long, String)}.</li>
 * </ul>
 *
 * <p>Routing: the byte[] overloads prefer the standalone channel when negotiated and fall
 * back to Cloud otherwise; the Path overload is Cloud-only (the channel protocol needs the
 * full buffer anyway).</p>
 */
public final class ModelUploadSession {
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile ModelUploadSession instance;
    private static volatile boolean serverLimitsKnown = false;
    private static volatile int lastMaxTotalBytes = 128 * 1024 * 1024;
    private static volatile int lastChunksPerTick = 4;

    private final String modelId;
    private final String fileName;
    /** Standalone channel only; null on Cloud sessions. */
    private final byte[] data;
    /** Cloud sessions only; null on the standalone channel. */
    private final Path source;
    private final boolean deleteSourceOnCompletion;
    private final String sha256;
    private final boolean syncSelectionOnComplete;
    /** Standalone channel only; null on Cloud sessions. */
    private final YsmUploadTransport sessionTransport;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile State state = State.STARTING;
    private volatile long sentBytes;
    private volatile Component message = Component.translatable("gui.sparkle_morpher.import.state.importing");
    // Standalone channel state machine.
    private volatile long uploadId = 0L;
    private volatile int chunkSize = 32_000;
    private volatile int chunksPerTick = 4;
    private volatile int nextOffset = 0;

    private ModelUploadSession(String modelId, String fileName, byte[] data, boolean syncSelectionOnComplete, YsmUploadTransport transport) {
        this.modelId = modelId;
        this.fileName = fileName;
        this.data = data;
        this.source = null;
        this.deleteSourceOnCompletion = false;
        this.sha256 = DigestUtil.sha256Hex(data);
        this.syncSelectionOnComplete = syncSelectionOnComplete;
        this.sessionTransport = transport;
    }

    private ModelUploadSession(String modelId, String fileName, Path source,
                               boolean deleteSourceOnCompletion) {
        this.modelId = modelId;
        this.fileName = fileName;
        this.data = null;
        this.source = source;
        this.deleteSourceOnCompletion = deleteSourceOnCompletion;
        this.sha256 = null;
        this.syncSelectionOnComplete = false;
        this.sessionTransport = null;
    }

    public static ModelUploadSession getInstance() {
        return instance;
    }

    /** Existing screens may pass bytes; the HTTP body is still streamed from a temporary file. */
    public static synchronized Component start(String modelId, String fileName, byte[] data) {
        return start(modelId, fileName, data, true, "PRIVATE");
    }

    public static synchronized Component start(String modelId, String fileName, byte[] data,
                                               boolean syncSelectionOnComplete) {
        return start(modelId, fileName, data, syncSelectionOnComplete, "PRIVATE");
    }

    public static synchronized Component start(String modelId, String fileName, byte[] data,
                                               boolean syncSelectionOnComplete, String visibility) {
        if (data == null || data.length == 0) {
            return Component.translatable("gui.sparkle_morpher.import.error.empty_file");
        }
        if (YsmUploadClientBridge.isChannelAvailable()) {
            return startOnUploadChannel(modelId, fileName, data, syncSelectionOnComplete);
        }
        try {
            Path temporary = Files.createTempFile("spm-cloud-upload-", extensionFor(fileName));
            Files.write(temporary, data);
            Component error = start(modelId, fileName, temporary, syncSelectionOnComplete, visibility, true);
            if (error != null) Files.deleteIfExists(temporary);
            return error;
        } catch (IOException error) {
            return Component.translatable("gui.sparkle_morpher.import.error.local_storage");
        }
    }

    /** Starts a Cloud upload without materializing the source in memory. */
    public static synchronized Component start(String modelId, String fileName, Path source,
                                               boolean syncSelectionOnComplete) {
        return start(modelId, fileName, source, syncSelectionOnComplete, "PRIVATE", false);
    }

    private static Component start(String modelId, String fileName, Path source,
                                   boolean ignoredSyncSelectionOnComplete,
                                   String visibility,
                                   boolean deleteSourceOnCompletion) {
        if (instance != null && !instance.isTerminal()) {
            return Component.translatable("gui.sparkle_morpher.import.error.in_progress");
        }
        ModelUploadTransport uploadTransport = CloudUploadRuntime.transport();
        if (uploadTransport == null || !CloudState.isAvailable()) {
            return Component.translatable("gui.sparkle_morpher.import.error.cloud_unavailable");
        }
        if (modelId == null || modelId.isBlank() || fileName == null || fileName.isBlank()) {
            return Component.translatable("gui.sparkle_morpher.import.error.invalid_model_id_or_hash");
        }
        final long totalBytes;
        final String sha256;
        try {
            totalBytes = Files.size(source);
            if (totalBytes <= 0 || totalBytes > Integer.MAX_VALUE) {
                return Component.translatable("gui.sparkle_morpher.import.error.empty_file");
            }
            sha256 = DigestUtil.sha256Hex(source);
        } catch (IOException error) {
            return Component.translatable("gui.sparkle_morpher.import.error.local_storage");
        }
        if (totalBytes > lastMaxTotalBytes) {
            return Component.translatable("gui.sparkle_morpher.import.error.server_limit", formatBytes(lastMaxTotalBytes));
        }
        ImportKind kind = ImportKind.fromFileName(fileName);
        if (kind == ImportKind.UNKNOWN) {
            return Component.translatable("gui.sparkle_morpher.import.error.invalid_extension");
        }
        try {
            if (kind == ImportKind.YSM && LegacyCompatModelFormat.detectCryptoVersion(Files.readAllBytes(source)) == -1) {
                return Component.translatable("gui.sparkle_morpher.import.error.invalid_ysm");
            }
        } catch (IOException error) {
            return Component.translatable("gui.sparkle_morpher.import.error.local_storage");
        }

        ModelUploadSession session = new ModelUploadSession(modelId, fileName, source, deleteSourceOnCompletion);
        instance = session;
        notifyListeners();
        ModelUploadTransport.UploadMetadata metadata = new ModelUploadTransport.UploadMetadata(
                modelId, fileName, kind.wireName, sha256, totalBytes, visibility);
        uploadTransport.upload(metadata, source, session::onProgress, session.cancelled::get)
                .whenComplete((result, error) -> session.complete(result, error));
        return null;
    }

    /**
     * Standalone {@code exspm_hserverysm_model} channel flow: validate, then handshake with
     * {@code sendStart}; chunk/finish pacing happens in {@link #tick()} once the server acks.
     */
    private static synchronized Component startOnUploadChannel(String modelId, String fileName, byte[] data,
                                                               boolean syncSelectionOnComplete) {
        if (instance != null && !instance.isTerminal()) {
            return Component.translatable("gui.sparkle_morpher.import.error.in_progress");
        }
        if (!NetworkHandler.isClientConnected()) {
            return Component.translatable("gui.sparkle_morpher.import.error.waiting_handshake");
        }
        if (serverLimitsKnown && data.length > lastMaxTotalBytes) {
            return Component.translatable("gui.sparkle_morpher.import.error.server_limit", formatBytes(lastMaxTotalBytes));
        }
        ImportKind kind = ImportKind.fromFileName(fileName);
        if (kind == ImportKind.UNKNOWN) {
            return Component.translatable("gui.sparkle_morpher.import.error.invalid_extension");
        }
        if (kind == ImportKind.YSM && !isYsmFile(data)) {
            return Component.translatable("gui.sparkle_morpher.import.error.invalid_ysm");
        }
        if (kind == ImportKind.ZIP && !isZipFile(data)) {
            return Component.translatable("gui.sparkle_morpher.import.error.invalid_zip");
        }
        YsmUploadTransport transport = YsmUploadTransport.INSTANCE;
        ModelUploadSession session = new ModelUploadSession(modelId, fileName, data, syncSelectionOnComplete, transport);
        instance = session;
        notifyListeners();
        transport.sendStart(modelId, fileName == null ? "" : fileName, data.length, session.sha256);
        return null;
    }

    public static boolean hasServerLimits() {
        return serverLimitsKnown;
    }

    public static int getLastMaxTotalBytes() {
        return lastMaxTotalBytes;
    }

    public static int getLastChunksPerTick() {
        return lastChunksPerTick;
    }

    public static String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static synchronized void clearIfTerminal() {
        if (instance != null && instance.isTerminal()) {
            instance = null;
            notifyListeners();
        }
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Standalone channel handshake ack (driven by {@link YsmUploadClientBridge}). */
    public static synchronized void onStartAck(long uploadId, byte status, int chunkSize, int maxTotalBytes, int chunksPerTick, String message) {
        if (maxTotalBytes > 0) {
            lastMaxTotalBytes = maxTotalBytes;
        }
        if (chunksPerTick > 0) {
            lastChunksPerTick = chunksPerTick;
        }
        serverLimitsKnown = true;
        ModelUploadSession session = instance;
        if (session == null || session.state != State.STARTING) {
            return;
        }
        if (status != 0) {
            session.fail(appendServerMessage(getRequestErrorText(status), message));
            notifyListeners();
            return;
        }
        session.uploadId = uploadId;
        session.chunkSize = Math.max(1, chunkSize);
        session.chunksPerTick = Math.max(1, chunksPerTick);
        session.state = State.UPLOADING;
        session.message = Component.translatable("gui.sparkle_morpher.import.state.server_uploading");
        notifyListeners();
    }

    /** Standalone channel final result (driven by {@link YsmUploadClientBridge}). */
    public static synchronized void onResult(long uploadId, byte status, String modelId, long h1, long h2, String message) {
        ModelUploadSession session = instance;
        if (session == null || session.uploadId != uploadId) {
            return;
        }
        if (status == 0) {
            session.state = State.COMPLETED;
            session.message = Component.translatable("gui.sparkle_morpher.import.state.imported_as", modelId);
            if (session.syncSelectionOnComplete) {
                ClientModelManager.onUploadedModelAvailable(modelId);
            } else {
                ClientModelManager.onUploadedModelImported(modelId);
            }
        } else {
            session.fail(appendServerMessage(getResponseErrorText(status), message));
        }
        notifyListeners();
    }

    /** Per-tick pacing for the standalone channel; no-op on Cloud sessions. */
    public static void tickCurrent() {
        ModelUploadSession session = instance;
        if (session != null) {
            session.tick();
        }
    }

    public static synchronized void failCurrent(Component reason) {
        ModelUploadSession session = instance;
        if (session == null || session.isTerminal()) return;
        session.cancelled.set(true);
        session.fail(reason);
        notifyListeners();
    }

    private static void notifyListeners() {
        ModelUploadSession session = instance;
        for (Listener listener : listeners) listener.onSessionUpdate(session);
    }

    private static boolean isYsmFile(byte[] data) {
        return LegacyCompatModelFormat.detectCryptoVersion(data) != -1;
    }

    private static boolean isZipFile(byte[] data) {
        return data.length >= 4
                && data[0] == 0x50
                && data[1] == 0x4b
                && (data[2] == 0x03 || data[2] == 0x05 || data[2] == 0x07)
                && (data[3] == 0x04 || data[3] == 0x06 || data[3] == 0x08);
    }

    private static Component getRequestErrorText(byte status) {
        return switch (status) {
            case 1 -> Component.translatable("gui.sparkle_morpher.import.error.model_exists");
            case 2 -> Component.translatable("gui.sparkle_morpher.import.error.file_exceeds_server_limit");
            case 3 -> Component.translatable("gui.sparkle_morpher.import.error.no_permission");
            case 4 -> Component.translatable("gui.sparkle_morpher.import.error.server_busy");
            case 5 -> Component.translatable("gui.sparkle_morpher.import.error.invalid_model_id_or_hash");
            case 6 -> Component.translatable("gui.sparkle_morpher.import.error.disabled_by_server");
            default -> Component.translatable("gui.sparkle_morpher.import.error.status", status);
        };
    }

    private static Component getResponseErrorText(byte status) {
        return switch (status) {
            case 1 -> Component.translatable("gui.sparkle_morpher.import.error.hash_mismatch");
            case 2 -> Component.translatable("gui.sparkle_morpher.import.error.server_parse_failed");
            case 3 -> Component.translatable("gui.sparkle_morpher.import.error.server_storage");
            case 4 -> Component.translatable("gui.sparkle_morpher.import.error.session_expired");
            case 5 -> Component.translatable("gui.sparkle_morpher.import.error.incomplete_upload");
            case 6 -> Component.translatable("gui.sparkle_morpher.import.error.server_rejected_write");
            case 8 -> Component.translatable("gui.sparkle_morpher.import.error.scan_not_visible");
            default -> Component.translatable("gui.sparkle_morpher.import.error.status", status);
        };
    }

    private static Component appendServerMessage(Component base, String serverMessage) {
        if (serverMessage == null || serverMessage.isEmpty() || isKnownServerMessage(serverMessage)) {
            return base;
        }
        MutableComponent result = base.copy();
        result.append(Component.literal(": "));
        result.append(Component.literal(serverMessage));
        return result;
    }

    private static boolean isKnownServerMessage(String serverMessage) {
        return switch (serverMessage.trim()) {
            case "Model import disabled",
                 "No import permission",
                 "Invalid model id or hash",
                 "File exceeds server limit",
                 "Model ID already exists",
                 "Session expired",
                 "Incomplete upload",
                 "Hash mismatch",
                 "Server failed to cache model",
                 "Server rejected write" -> true;
            default -> false;
        };
    }

    /** Standalone channel chunk pacing. */
    private synchronized void tick() {
        if (state != State.UPLOADING || sessionTransport == null || data == null) {
            return;
        }
        long perfStart = PerformanceProfiler.start();
        int budget = Math.max(1, chunksPerTick);
        int chunks = 0;
        int bytes = 0;
        for (int i = 0; i < budget && nextOffset < data.length; i++) {
            int end = Math.min(nextOffset + chunkSize, data.length);
            int length = end - nextOffset;
            sessionTransport.sendChunk(uploadId, nextOffset, data, nextOffset, length);
            nextOffset = end;
            chunks++;
            bytes += length;
        }
        PerformanceProfiler.logElapsed("client_upload_tick", modelId, perfStart,
                "chunks=" + chunks + " bytes=" + bytes + " sent=" + nextOffset + "/" + data.length);
        if (nextOffset >= data.length) {
            state = State.FINISHING;
            message = Component.translatable("gui.sparkle_morpher.import.state.verifying");
            sessionTransport.sendFinish(uploadId);
        }
        notifyListeners();
    }

    private void onProgress(long sent, long total) {
        sentBytes = Math.min(Math.max(sent, 0), total);
        state = State.UPLOADING;
        message = Component.translatable("gui.sparkle_morpher.import.state.importing");
        notifyListeners();
    }

    private synchronized void complete(ModelUploadTransport.UploadResult result, Throwable error) {
        try {
            if (error != null) {
                fail(Component.literal(rootMessage(error)));
            } else if (cancelled.get()) {
                fail(Component.translatable("gui.sparkle_morpher.resource_station.cancelled"));
            } else {
                sentBytes = result.byteLength();
                state = State.COMPLETED;
                message = Component.translatable("gui.sparkle_morpher.import.state.imported_as", result.assetId());
                // Cloud assets are not selected through the legacy Minecraft packet channel.
                ClientModelManager.onUploadedModelImported(result.assetId());
            }
        } finally {
            if (deleteSourceOnCompletion) {
                try { Files.deleteIfExists(source); } catch (IOException ignored) { }
            }
            notifyListeners();
        }
    }

    private void fail(Component reason) {
        state = State.FAILED;
        message = reason;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String extensionFor(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".bbmodel")) return ".bbmodel";
        if (lower.endsWith(".gltf")) return ".gltf";
        if (lower.endsWith(".glb")) return ".glb";
        return ".ysm";
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.FAILED;
    }

    public State getState() { return state; }
    public String getModelId() { return modelId; }
    public String getFileName() { return fileName; }

    public int getTotalBytes() {
        if (data != null) {
            return data.length;
        }
        try { return (int) Math.min(Integer.MAX_VALUE, Files.size(source)); }
        catch (IOException ignored) { return 0; }
    }

    public int getSentBytes() {
        if (data != null) {
            return Math.min(nextOffset, data.length);
        }
        return (int) Math.min(Integer.MAX_VALUE, sentBytes);
    }

    public Component getMessage() { return message; }

    public float getProgress() {
        if (state == State.COMPLETED) {
            return 1f;
        }
        int total = getTotalBytes();
        return total <= 0 ? 0f : Math.min(1f, (float) getSentBytes() / total);
    }

    public enum State { STARTING, UPLOADING, FINISHING, COMPLETED, FAILED }

    private enum ImportKind {
        YSM("ysm"), ZIP("zip"), BBMODEL("bbmodel"), GLTF("gltf"), GLB("glb"), UNKNOWN("");
        private final String wireName;
        ImportKind(String wireName) { this.wireName = wireName; }
        private static ImportKind fromFileName(String fileName) {
            if (fileName == null) return UNKNOWN;
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".ysm")) return YSM;
            if (lower.endsWith(".zip")) return ZIP;
            if (lower.endsWith(".bbmodel")) return BBMODEL;
            if (lower.endsWith(".gltf")) return GLTF;
            if (lower.endsWith(".glb")) return GLB;
            return UNKNOWN;
        }
    }

    public interface Listener { void onSessionUpdate(ModelUploadSession session); }
}
