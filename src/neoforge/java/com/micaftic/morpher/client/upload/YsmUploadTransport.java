package com.micaftic.morpher.client.upload;

import com.micaftic.morpher.core.api.network.upload.ModelUploadTransport;
import com.micaftic.exspm_hserverysm_model.network.YsmUploadPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Upload transport for the independent {@code exspm_hserverysm_model:1} channel.
 *
 * <p>Used automatically by {@link ModelUploadSession} when the server negotiates
 * the upload channel (i.e. the standalone server-only companion mod is running).
 * Packets use the {@code YsmUploadPackets} sub-protocol shared with that mod.</p>
 */
public final class YsmUploadTransport implements ModelUploadTransport {

    public static final YsmUploadTransport INSTANCE = new YsmUploadTransport();

    private YsmUploadTransport() {
    }

    @Override
    public boolean isAvailable() {
        return YsmUploadClientBridge.isChannelAvailable();
    }

    @Override
    public void sendStart(String modelId, String fileName, int dataLength, String sha256) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(YsmUploadPackets.DISC_START);
        YsmUploadPackets.Start.encode(new YsmUploadPackets.Start(modelId, fileName, dataLength, sha256), buf);
        YsmUploadClientBridge.sendToServer(buf);
    }

    @Override
    public void sendChunk(long uploadId, int nextOffset, byte[] data, int dataOffset, int length) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(YsmUploadPackets.DISC_CHUNK);
        YsmUploadPackets.Chunk.encode(new YsmUploadPackets.Chunk(uploadId, nextOffset, data, dataOffset, length), buf);
        YsmUploadClientBridge.sendToServer(buf);
    }

    @Override
    public void sendFinish(long uploadId) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(YsmUploadPackets.DISC_FINISH);
        YsmUploadPackets.Finish.encode(new YsmUploadPackets.Finish(uploadId), buf);
        YsmUploadClientBridge.sendToServer(buf);
    }
}
