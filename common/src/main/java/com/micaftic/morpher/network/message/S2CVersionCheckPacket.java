package com.micaftic.morpher.network.message;

import com.micaftic.morpher.core.api.network.PacketContext;
import com.micaftic.morpher.model.ServerModelManager;
import com.micaftic.morpher.network.ClientNetworkBridge;
import com.micaftic.morpher.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;

public class S2CVersionCheckPacket {
    private final String version;
    private final boolean oysmServer;
    private final boolean allowUpload;

    public S2CVersionCheckPacket() {
        this(NetworkHandler.VERSION, true, ServerModelManager.isModelUploadAllowed());
    }

    private S2CVersionCheckPacket(String version, boolean oysmServer, boolean allowUpload) {
        this.version = version;
        this.oysmServer = oysmServer;
        this.allowUpload = allowUpload;
    }

    public static S2CVersionCheckPacket decode(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        // 协议版本匹配（官方 YSM 2.6.x 也用 "2.6.0"）只代表"对方是 YSM 兼容服务器"，
        // 不代表支持 SPM 的上传协议。只有带 open_ysm:v1 品牌标记的才是 SPM/OpenYSM 服务器，
        // 才会额外声明 allowUpload；官方 YSM 服务器无此品牌，必须保持上传禁用，
        // 否则 SPM 客户端向官方服务器发 70-74 上传包会触发 Invalid index 解码崩溃断开连接。
        boolean oysmServer = NetworkHandler.VERSION.equals(version);
        boolean allowUpload = false;
        if (buf.readableBytes() > 0) {
            String brand = buf.readUtf();
            if ("open_ysm:v1".equals(brand) && buf.readableBytes() > 0) {
                oysmServer = true;
                allowUpload = buf.readBoolean();
            }
        }
        return new S2CVersionCheckPacket(version, oysmServer, allowUpload);
    }

    public static void encode(S2CVersionCheckPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.version);
        buf.writeUtf("open_ysm:v1");
        buf.writeBoolean(message.allowUpload);
    }

    public static void handle(S2CVersionCheckPacket message, PacketContext ctx) {
        ClientNetworkBridge.handle(ctx, "handleVersionCheck", message, ctx.getConnection());
    }

    public String getVersion() {
        return this.version;
    }

    public boolean isOysmServer() {
        return this.oysmServer;
    }

    public boolean isAllowUpload() {
        return this.allowUpload;
    }
}
