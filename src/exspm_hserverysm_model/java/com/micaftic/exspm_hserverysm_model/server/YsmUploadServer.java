package com.micaftic.exspm_hserverysm_model.server;

import com.micaftic.exspm_hserverysm_model.YsmUploadMod;
import com.micaftic.exspm_hserverysm_model.network.YsmUploadPackets;
import com.micaftic.exspm_hserverysm_model.network.YsmUploadPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side dispatcher for the {@code exspm_hserverysm_model:1} channel. Decodes the
 * sub-packet stream (discriminator byte + fields), keeps an in-memory session
 * per in-flight upload, stores the finished file into the official YSM custom
 * folder and best-effort triggers a YSM model reload.
 *
 * <p>All packet handling is moved to the server thread via
 * {@link IPayloadContext#enqueueWork(Runnable)} so session state and file I/O
 * stay on the main thread.</p>
 */
public final class YsmUploadServer {

    private static final long SESSION_TIMEOUT_MS = 60_000L;

    private static final Map<Long, UploadSession> SESSIONS = new ConcurrentHashMap<>();

    private YsmUploadServer() {
    }

    public static void handle(YsmUploadPayload payload, IPayloadContext context) {
        if (context.flow() != PacketFlow.SERVERBOUND) {
            // This mod never sends upload data clientbound; ignore anything else.
            return;
        }
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = payload.buf();
                buf.resetReaderIndex();
                int disc = buf.readUnsignedByte();
                dispatch(disc, buf, context);
            } catch (Throwable t) {
                YsmUploadMod.LOGGER.warn("Failed to handle upload payload", t);
            }
        });
    }

    private static void dispatch(int disc, FriendlyByteBuf buf, IPayloadContext context) {
        switch (disc) {
            case YsmUploadPackets.DISC_START -> handleStart(YsmUploadPackets.Start.decode(buf), context);
            case YsmUploadPackets.DISC_CHUNK -> handleChunk(YsmUploadPackets.Chunk.decode(buf), context);
            case YsmUploadPackets.DISC_FINISH -> handleFinish(YsmUploadPackets.Finish.decode(buf), context);
            default -> YsmUploadMod.LOGGER.warn("Unknown upload packet discriminator={}", disc);
        }
    }

    private static void handleStart(YsmUploadPackets.Start start, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player == null) {
            return;
        }
        cleanupExpired();
        if (!YsmUploadConfig.allowUpload()) {
            sendStartAck(context, new YsmUploadPackets.StartAck(0L, (byte) 6, 0, 0, 0, "Upload disabled by server config"));
            return;
        }
        if (YsmUploadConfig.requireOp() && !player.hasPermissions(2)) {
            sendStartAck(context, new YsmUploadPackets.StartAck(0L, (byte) 6, 0, 0, 0, "Permission denied"));
            return;
        }
        String modelId = YsmModelStore.sanitizeModelId(start.modelId());
        String fileName = start.fileName() == null ? "" : start.fileName();
        String fileKind = fileKindOf(fileName);
        if (modelId == null) {
            sendStartAck(context, new YsmUploadPackets.StartAck(0L, (byte) 6, 0, 0, 0, "Invalid model id"));
            return;
        }
        if (!"ysm".equals(fileKind) && !"zip".equals(fileKind)) {
            sendStartAck(context, new YsmUploadPackets.StartAck(0L, (byte) 6, 0, 0, 0, "Unsupported format: ." + (fileKind.isEmpty() ? "?" : fileKind)));
            return;
        }
        int maxBytes = YsmUploadConfig.maxModelBytes();
        if (start.totalBytes() <= 0 || start.totalBytes() > maxBytes) {
            sendStartAck(context, new YsmUploadPackets.StartAck(0L, (byte) 6, 0, 0, 0, "File size out of range"));
            return;
        }
        long uploadId;
        do {
            uploadId = randomLong();
        } while (uploadId == 0L || SESSIONS.containsKey(uploadId));
        SESSIONS.put(uploadId, new UploadSession(uploadId, player.getUUID(), modelId, fileName, start.totalBytes(), start.sha256()));
        sendStartAck(context, new YsmUploadPackets.StartAck(uploadId, (byte) 0, YsmUploadConfig.chunkSize(), maxBytes, 4, ""));
    }

    private static void handleChunk(YsmUploadPackets.Chunk chunk, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player == null) {
            return;
        }
        UploadSession session = SESSIONS.get(chunk.uploadId());
        if (session == null || !session.owner().equals(player.getUUID())) {
            return;
        }
        if (!session.appendChunk(chunk.offset(), chunk.data())) {
            session.markFailed();
        }
    }

    private static void handleFinish(YsmUploadPackets.Finish finish, IPayloadContext context) {
        ServerPlayer player = sender(context);
        if (player == null) {
            return;
        }
        UploadSession session = SESSIONS.remove(finish.uploadId());
        if (session == null || !session.owner().equals(player.getUUID())) {
            sendResult(context, new YsmUploadPackets.Result(finish.uploadId(), (byte) 4, "", "Session expired"));
            return;
        }
        if (session.isFailed() || !session.isComplete()) {
            sendResult(context, new YsmUploadPackets.Result(finish.uploadId(), (byte) 5, "", "Incomplete upload"));
            return;
        }
        byte[] data = session.copyData();
        if (!session.sha256().isEmpty()) {
            String actual = sha256Hex(data);
            if (!session.sha256().equalsIgnoreCase(actual)) {
                sendResult(context, new YsmUploadPackets.Result(finish.uploadId(), (byte) 1, "", "Hash mismatch"));
                return;
            }
        }
        YsmModelStore.Result stored = YsmModelStore.store(session.fileKind(), data, session.modelId());
        if (!stored.success()) {
            sendResult(context, new YsmUploadPackets.Result(finish.uploadId(), (byte) 2, "", stored.message()));
            return;
        }
        String modelId = session.modelId();
        YsmUploadMod.LOGGER.info("Stored uploaded model '{}' -> {} ({} bytes)", modelId, stored.path(), data.length);
        YsmModelReloader.requestReload(player.getServer());
        sendResult(context, new YsmUploadPackets.Result(finish.uploadId(), (byte) 0, modelId, "Uploaded"));
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastTouchMillis() > SESSION_TIMEOUT_MS);
    }

    private static ServerPlayer sender(IPayloadContext context) {
        return context.player() instanceof ServerPlayer sp ? sp : null;
    }

    private static void sendStartAck(IPayloadContext context, YsmUploadPackets.StartAck ack) {
        ServerPlayer player = sender(context);
        if (player == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(YsmUploadPackets.DISC_START_ACK);
        YsmUploadPackets.StartAck.encode(ack, buf);
        PacketDistributor.sendToPlayer(player, new YsmUploadPayload(buf));
    }

    private static void sendResult(IPayloadContext context, YsmUploadPackets.Result result) {
        ServerPlayer player = sender(context);
        if (player == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(YsmUploadPackets.DISC_RESULT);
        YsmUploadPackets.Result.encode(result, buf);
        PacketDistributor.sendToPlayer(player, new YsmUploadPayload(buf));
    }

    private static String fileKindOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static long randomLong() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
