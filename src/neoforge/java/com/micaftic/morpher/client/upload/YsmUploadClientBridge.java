package com.micaftic.morpher.client.upload;

import com.micaftic.exspm_hserverysm_model.network.YsmUploadPackets;
import com.micaftic.exspm_hserverysm_model.network.YsmUploadPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side bridge for the independent {@code exspm_hserverysm_model:1} channel.
 *
 * <p>When the server also runs the standalone server-only upload mod, this
 * channel is negotiated and the client can send model files straight into the
 * official YSM custom folder instead of using the legacy SPM server channel.
 * This class owns the availability probe, the outbound packet sending and the
 * inbound StartAck/Result handling for that channel.</p>
 */
public final class YsmUploadClientBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_upload_client");

    private YsmUploadClientBridge() {
    }

    /** Whether the current server connection negotiated the upload channel. */
    public static boolean isChannelAvailable() {
        try {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return false;
            }
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null || listener.getConnection() == null) {
                return false;
            }
            return NetworkRegistry.hasChannel(listener, YsmUploadPayload.CHANNEL_ID);
        } catch (Throwable t) {
            LOGGER.debug("Upload channel probe failed", t);
            return false;
        }
    }

    /** Sends one upload payload to the server on the upload channel. */
    public static void sendToServer(FriendlyByteBuf buf) {
        PacketDistributor.sendToServer(new YsmUploadPayload(buf));
    }

    /** Decodes and dispatches clientbound upload messages (StartAck / Result). */
    public static void handle(YsmUploadPayload payload, IPayloadContext context) {
        if (context.flow() != PacketFlow.CLIENTBOUND) {
            return;
        }
        context.enqueueWork(() -> {
            try {
                FriendlyByteBuf buf = payload.buf();
                buf.resetReaderIndex();
                int disc = buf.readUnsignedByte();
                switch (disc) {
                    case YsmUploadPackets.DISC_START_ACK -> {
                        YsmUploadPackets.StartAck ack = YsmUploadPackets.StartAck.decode(buf);
                        ModelUploadSession.onStartAck(ack.uploadId(), ack.status(), ack.chunkSize(),
                                ack.maxTotalBytes(), ack.chunksPerTick(), ack.message());
                    }
                    case YsmUploadPackets.DISC_RESULT -> {
                        YsmUploadPackets.Result result = YsmUploadPackets.Result.decode(buf);
                        ModelUploadSession.onResult(result.uploadId(), result.status(), result.modelId(),
                                0L, 0L, result.message());
                    }
                    default -> LOGGER.warn("Unknown upload packet discriminator={}", disc);
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to handle upload payload", t);
            }
        });
    }
}
