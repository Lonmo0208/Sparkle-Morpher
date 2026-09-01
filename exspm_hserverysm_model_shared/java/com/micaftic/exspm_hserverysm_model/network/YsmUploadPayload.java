package com.micaftic.exspm_hserverysm_model.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Single envelope payload for the independent upload channel. It wraps the
 * sub-packet byte stream (discriminator byte + fields) the same way the main
 * mod wraps its own payload. The channel is registered as play-bidirectional so
 * both the client (Sparkle's Morpher) and the dedicated server mod share the
 * exact same payload type without conflicting with the official YSM channel.
 *
 * <p>This class is compiled into BOTH jars: the Sparkle's Morpher client jar
 * (for sending/receiving on the new channel) and the standalone server-only
 * upload mod jar. Both copies are byte-identical and never loaded together in
 * the intended deployment (client = SPM only, server = official YSM + server mod).</p>
 */
public record YsmUploadPayload(FriendlyByteBuf buf) implements CustomPacketPayload {

    public static final String VERSION = "1";

    public static final ResourceLocation CHANNEL_ID =
            ResourceLocation.fromNamespaceAndPath("exspm_hserverysm_model", "1");

    public static CustomPacketPayload.Type<YsmUploadPayload> TYPE;

    /** Codec shared by both the SPM client and the standalone server mod. */
    public static final StreamCodec<FriendlyByteBuf, YsmUploadPayload> CODEC = StreamCodec.of(
            (target, payload) -> {
                FriendlyByteBuf src = payload.buf;
                src.resetReaderIndex();
                target.writeBytes(src, src.readerIndex(), src.readableBytes());
            },
            source -> {
                FriendlyByteBuf copy = new FriendlyByteBuf(Unpooled.buffer(source.readableBytes()));
                source.readBytes(copy);
                return new YsmUploadPayload(copy);
            }
    );

    /** Initializes {@link #TYPE}; safe to call from any mod that ships this class. */
    public static void initType() {
        TYPE = new CustomPacketPayload.Type<>(CHANNEL_ID);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
