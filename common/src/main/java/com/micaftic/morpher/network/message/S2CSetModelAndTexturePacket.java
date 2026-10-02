package com.micaftic.morpher.network.message;

import com.micaftic.morpher.core.api.network.PacketContext;
import com.micaftic.morpher.network.ClientNetworkBridge;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class S2CSetModelAndTexturePacket {
    private final int entityId;
    private final String modelId;
    private final String textureId;
    private final boolean disabled;
    private final S2CSyncPlayerStatePacket entityModelSync;
    private final UUID playerUuid;

    public S2CSetModelAndTexturePacket(int entityId, String modelId, String textureId, boolean disabled, S2CSyncPlayerStatePacket playerState) {
        this(entityId, modelId, textureId, disabled, playerState, null);
    }

    public S2CSetModelAndTexturePacket(int entityId, String modelId, String textureId, boolean disabled, S2CSyncPlayerStatePacket playerState, @Nullable UUID playerUuid) {
        this.entityId = entityId;
        this.modelId = modelId;
        this.textureId = textureId;
        this.entityModelSync = playerState;
        this.disabled = disabled;
        this.playerUuid = playerUuid;
    }

    public static void encode(S2CSetModelAndTexturePacket other, FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeVarInt(other.entityId);
        friendlyByteBuf.writeUtf(other.modelId);
        friendlyByteBuf.writeUtf(other.textureId);
        friendlyByteBuf.writeBoolean(other.disabled);
        S2CSyncPlayerStatePacket.encode(other.entityModelSync, friendlyByteBuf);
        // 追加在尾部：旧客户端会忽略多出来的字节；新客户端读旧服务端的包时靠 isReadable 兜住。
        if (other.playerUuid != null) {
            friendlyByteBuf.writeUUID(other.playerUuid);
        }
    }

    public static S2CSetModelAndTexturePacket decode(FriendlyByteBuf friendlyByteBuf) {
        int entityId = friendlyByteBuf.readVarInt();
        String modelId = friendlyByteBuf.readUtf();
        String textureId = friendlyByteBuf.readUtf();
        boolean disabled = friendlyByteBuf.readBoolean();
        S2CSyncPlayerStatePacket entityModelSync = S2CSyncPlayerStatePacket.decode(friendlyByteBuf);
        UUID playerUuid = friendlyByteBuf.readableBytes() >= 16 ? friendlyByteBuf.readUUID() : null;
        return new S2CSetModelAndTexturePacket(entityId, modelId, textureId, disabled, entityModelSync, playerUuid);
    }

    public static void handle(S2CSetModelAndTexturePacket other, PacketContext ctx) {
        ClientNetworkBridge.handle(ctx, "handleSetModelAndTexture", other);
    }

    public int getEntityId() {
        return this.entityId;
    }

    public String getModelId() {
        return this.modelId;
    }

    public String getTextureId() {
        return this.textureId;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    public S2CSyncPlayerStatePacket getEntityModelSync() {
        return this.entityModelSync;
    }

    /**
     * 模型归属玩家的 UUID。远景模组（VSS/voxy）的替身实体不在客户端的实体列表里，只按实体 ID
     * 没法把模型信息落到玩家身上；有了 UUID 才能在替身出现时直接把模型建出来。
     * 旧服务端不带这个字段，返回 null。
     */
    @Nullable
    public UUID getPlayerUuid() {
        return this.playerUuid;
    }
}
