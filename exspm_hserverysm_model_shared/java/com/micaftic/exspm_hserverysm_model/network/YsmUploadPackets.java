package com.micaftic.exspm_hserverysm_model.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Sub-packet definitions for the exspm_hserverysm_model channel. The first byte of the
 * payload buffer is the discriminator; the remaining bytes are encoded by the
 * matching record below. Status codes follow the SPM upload semantics so the
 * client can reuse its existing error translations.
 *
 * <p>This class is compiled into BOTH jars (SPM client jar and the standalone
 * server-only upload mod jar). Both copies are byte-identical.</p>
 */
public final class YsmUploadPackets {

    public static final int DISC_START = 0;      // C2S
    public static final int DISC_START_ACK = 1;  // S2C
    public static final int DISC_CHUNK = 2;      // C2S
    public static final int DISC_FINISH = 3;     // C2S
    public static final int DISC_RESULT = 4;     // S2C

    private YsmUploadPackets() {
    }

    /** C2S: client requests to start an upload session. */
    public record Start(String modelId, String fileName, int totalBytes, String sha256) {
        public static void encode(Start p, FriendlyByteBuf buf) {
            buf.writeUtf(p.modelId == null ? "" : p.modelId);
            buf.writeUtf(p.fileName == null ? "" : p.fileName);
            buf.writeVarInt(p.totalBytes);
            buf.writeUtf(p.sha256 == null ? "" : p.sha256);
        }

        public static Start decode(FriendlyByteBuf buf) {
            return new Start(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readUtf());
        }
    }

    /** S2C: server acknowledges the start request. status 0 = accepted. */
    public record StartAck(long uploadId, byte status, int chunkSize, int maxTotalBytes, int chunksPerTick, String message) {
        public static void encode(StartAck p, FriendlyByteBuf buf) {
            buf.writeVarLong(p.uploadId);
            buf.writeByte(p.status);
            buf.writeVarInt(p.chunkSize);
            buf.writeVarInt(p.maxTotalBytes);
            buf.writeVarInt(p.chunksPerTick);
            buf.writeUtf(p.message == null ? "" : p.message);
        }

        public static StartAck decode(FriendlyByteBuf buf) {
            return new StartAck(buf.readVarLong(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
        }
    }

    /** C2S: one data chunk. offset is the absolute byte offset in the file. */
    public record Chunk(long uploadId, int offset, byte[] data, int dataOffset, int dataLength) {
        public Chunk(long uploadId, int offset, byte[] data) {
            this(uploadId, offset, data, 0, data == null ? 0 : data.length);
        }

        public static void encode(Chunk p, FriendlyByteBuf buf) {
            buf.writeVarLong(p.uploadId);
            buf.writeVarInt(p.offset);
            buf.writeVarInt(p.dataLength);
            buf.writeBytes(p.data, p.dataOffset, p.dataLength);
        }

        public static Chunk decode(FriendlyByteBuf buf) {
            long id = buf.readVarLong();
            int offset = buf.readVarInt();
            byte[] data = buf.readByteArray();
            return new Chunk(id, offset, data);
        }
    }

    /** C2S: all chunks sent, server should assemble, store and reload. */
    public record Finish(long uploadId) {
        public static void encode(Finish p, FriendlyByteBuf buf) {
            buf.writeVarLong(p.uploadId);
        }

        public static Finish decode(FriendlyByteBuf buf) {
            return new Finish(buf.readVarLong());
        }
    }

    /** S2C: upload result. status 0 = success. */
    public record Result(long uploadId, byte status, String modelId, String message) {
        public static void encode(Result p, FriendlyByteBuf buf) {
            buf.writeVarLong(p.uploadId);
            buf.writeByte(p.status);
            buf.writeUtf(p.modelId == null ? "" : p.modelId);
            buf.writeUtf(p.message == null ? "" : p.message);
        }

        public static Result decode(FriendlyByteBuf buf) {
            return new Result(buf.readVarLong(), buf.readByte(), buf.readUtf(), buf.readUtf());
        }
    }
}
